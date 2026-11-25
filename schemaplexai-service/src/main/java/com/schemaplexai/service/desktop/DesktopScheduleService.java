package com.schemaplexai.service.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.service.agent.AgentService;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import com.schemaplexai.service.notification.InAppMessageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DesktopScheduleService {
    private static final Set<String> ACTIVE = Set.of("pending", "queued", "running", "paused", "waiting",
            "waiting_approval", "interrupted", "pending_approval", "waiting_for_approval");
    private static final String OWN = " FROM sf_desktop_schedule WHERE tenant_id=? AND owner_id=? AND NOT deleted";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final DesktopCapabilityService capabilities;
    private final DesktopWorkspaceService workspace;
    private final DesktopWorkflowService workflows;
    private final AgentService agents;
    private final AgentExecutionMapper agentExecutions;
    private final WorkflowInstanceMapper workflowInstances;
    private final InAppMessageService notices;

    public record Target(@NotBlank String type, @NotBlank @Size(max = 64) String id) {}
    public record Definition(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 32000) String prompt,
                             @Valid @NotNull Target target, @Size(max = 64) String projectId,
                             @NotNull DesktopScheduleRule schedule, Boolean notifyInApp,
                             @NotBlank @Size(max = 128) String clientRequestId, Long expectedRevision) {}
    public record Schedule(String id, String tenantId, String ownerId, Definition definition,
                           long revision, boolean enabled, OffsetDateTime nextRunAt) {}
    public record Run(String id, String status, String executionType, String executionId,
                      OffsetDateTime scheduledAt, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                      String errorSummary) {}
    private record Claim(Schedule schedule, String runId) {}

    private final RowMapper<Schedule> schedules = (rs, index) -> new Schedule(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("owner_id"),
            decode(rs.getString("definition")), rs.getLong("revision"), rs.getBoolean("enabled"),
            rs.getObject("next_run_at", OffsetDateTime.class));
    private static final RowMapper<Run> RUN = (rs, index) -> new Run(rs.getString("id"), rs.getString("status"),
            rs.getString("execution_type"), rs.getString("execution_id"),
            rs.getObject("scheduled_at", OffsetDateTime.class), rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class), rs.getString("error_summary"));

    public PageResult<Schedule> list(int page, int size) {
        Employee employee = Employee.current();
        long count = jdbc.queryForObject("SELECT count(*)" + OWN, Long.class, employee.tenantId(), employee.userId());
        var rows = jdbc.query("SELECT *" + OWN + " ORDER BY updated_at DESC,id LIMIT ? OFFSET ?", schedules,
                employee.tenantId(), employee.userId(), size, DesktopWorkspaceService.offset(page, size));
        return new PageResult<>(rows, count, page, size);
    }

    public Schedule get(String id) {
        return get(id, false);
    }

    private Schedule get(String id, boolean lock) {
        Employee employee = Employee.current();
        return jdbc.query("SELECT *" + OWN + " AND id=?" + (lock ? " FOR UPDATE" : ""),
                schedules, employee.tenantId(), employee.userId(), id).stream().findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND));
    }

    public Schedule create(Definition definition) {
        Employee employee = Employee.current();
        validateTarget(definition);
        var next = requireFuture(definition.schedule());
        jdbc.update("""
                INSERT INTO sf_desktop_schedule(id,tenant_id,owner_id,request_id,definition,next_run_at)
                VALUES(?,?,?,?,CAST(? AS jsonb),?) ON CONFLICT(tenant_id,owner_id,request_id) DO NOTHING
                """, UUID.randomUUID().toString(), employee.tenantId(), employee.userId(),
                definition.clientRequestId(), encode(definition), next);
        var row = jdbc.query("SELECT *" + OWN + " AND request_id=?", schedules, employee.tenantId(),
                employee.userId(), definition.clientRequestId()).stream().findFirst()
                .orElseThrow(() -> new BusinessException(409, "原任务已删除"));
        if (!row.definition().equals(definition)) {
            throw new BusinessException(409, "请求编号已用于其他任务配置");
        }
        return row;
    }

    public Schedule update(String id, Definition definition) {
        validateTarget(definition);
        var next = requireFuture(definition.schedule());
        var current = get(id);
        DesktopWorkspaceService.revision(jdbc.update("""
                UPDATE sf_desktop_schedule SET definition=CAST(? AS jsonb), next_run_at=?,
                    updated_at=now(),revision=revision+1 WHERE id=? AND revision=? AND NOT deleted
                """, encode(definition), current.enabled() ? next : null, id, definition.expectedRevision()));
        return get(id);
    }

    public Schedule toggle(String id, long revision, boolean enabled) {
        var current = get(id);
        if (enabled) validateTarget(current.definition());
        OffsetDateTime next = enabled ? requireFuture(current.definition().schedule()) : null;
        DesktopWorkspaceService.revision(jdbc.update("""
                UPDATE sf_desktop_schedule SET enabled=?,next_run_at=?,revision=revision+1,
                    updated_at=now() WHERE id=? AND revision=? AND NOT deleted
                """, enabled, next, id, revision));
        return get(id);
    }

    public void delete(String id, long revision) {
        get(id);
        DesktopWorkspaceService.revision(jdbc.update("""
                UPDATE sf_desktop_schedule SET enabled=false, next_run_at=null, deleted=true,
                    revision=revision+1,updated_at=now() WHERE id=? AND revision=? AND NOT deleted
                """, id, revision));
    }

    public PageResult<Run> runs(String id, int page, int size) {
        get(id);
        long count = jdbc.queryForObject("SELECT count(*) FROM sf_desktop_schedule_run WHERE schedule_id=?", Long.class, id);
        var rows = jdbc.query("""
                SELECT * FROM sf_desktop_schedule_run WHERE schedule_id=?
                ORDER BY scheduled_at DESC,id LIMIT ? OFFSET ?
                """, RUN, id, size, DesktopWorkspaceService.offset(page, size));
        return new PageResult<>(rows, count, page, size);
    }

    public Run run(String id, long revision, String requestId) {
        Claim claim = transaction.execute(status -> {
            var schedule = get(id, true);
            var previous = jdbc.query("SELECT * FROM sf_desktop_schedule_run WHERE schedule_id=? AND request_id=?",
                    RUN, id, requestId);
            if (!previous.isEmpty()) return new Claim(null, previous.getFirst().id());
            if (revision != schedule.revision()) throw new BusinessException(409, "任务配置已变更");
            validateTarget(schedule.definition());
            return claim(schedule, OffsetDateTime.now(), requestId);
        });
        if (claim.schedule() != null) dispatch(claim);
        return jdbc.queryForObject("SELECT * FROM sf_desktop_schedule_run WHERE id=?", RUN, claim.runId());
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void tick() {
        try {
            List<Schedule> due = jdbc.query("""
                    SELECT * FROM sf_desktop_schedule WHERE enabled AND NOT deleted
                    AND next_run_at<=now() ORDER BY next_run_at LIMIT 30
                    """, schedules);
            for (Schedule row : due) asEmployee(row, () -> due(row));
            List<Schedule> running = jdbc.query("""
                    SELECT s.* FROM sf_desktop_schedule s WHERE EXISTS (
                        SELECT 1 FROM sf_desktop_schedule_run r WHERE r.schedule_id=s.id
                        AND r.status IN ('submitting','running'))
                    """, schedules);
            for (Schedule row : running) asEmployee(row, () -> refresh(row));
        } catch (RuntimeException ex) {
            log.warn("个人调度扫描失败: {}", ex.getClass().getSimpleName());
        }
    }

    private void asEmployee(Schedule row, Runnable action) {
        try {
            SecurityUtil.setCurrentTenantId(row.tenantId());
            SecurityUtil.setCurrentUserId(row.ownerId());
            action.run();
        } catch (RuntimeException ex) {
            log.warn("个人调度处理失败: scheduleId={}, type={}", row.id(), ex.getClass().getSimpleName());
        } finally {
            SecurityUtil.clear();
        }
    }

    private void due(Schedule row) {
        Claim claimed = transaction.execute(status -> {
            Schedule current = get(row.id(), true);
            if (!current.enabled() || current.nextRunAt() == null || current.nextRunAt().isAfter(OffsetDateTime.now())) {
                return null;
            }
            Instant now = Instant.now();
            var next = current.definition().schedule().next(now);
            jdbc.update("UPDATE sf_desktop_schedule SET next_run_at=?,enabled=? WHERE id=?",
                    next, next != null, row.id());
            if (current.nextRunAt().toInstant().plusSeconds(90).isBefore(now)) return null;
            return claim(current, current.nextRunAt(), null);
        });
        if (claimed != null) dispatch(claimed);
    }

    private Claim claim(Schedule schedule, OffsetDateTime time, String requestId) {
        refresh(schedule);
        String type = "workflow".equals(schedule.definition().target().type()) ? "workflow" : "agent";
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM sf_desktop_schedule_run
                WHERE schedule_id=? AND status IN ('running','submitting'))
                """, Boolean.class, schedule.id()));
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sf_desktop_schedule_run(id,schedule_id,request_id,scheduled_at,status,execution_type,finished_at)
                VALUES(?,?,?,?,?,?,?)
                """, id, schedule.id(), requestId, time, active ? "skipped" : "submitting", type,
                active ? OffsetDateTime.now() : null);
        return new Claim(active ? null : schedule, id);
    }

    private void dispatch(Claim claim) {
        if (claim.schedule() == null) return;
        var definition = claim.schedule().definition();
        try {
            boolean ownerActive = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM sf_user WHERE id=? AND tenant_id=? AND status='active' AND deleted=0)
                    """, Boolean.class, claim.schedule().ownerId(), claim.schedule().tenantId()));
            if (!ownerActive) throw new BusinessException(ResultCode.FORBIDDEN);
            String target = validateTarget(definition);
            String executionId;
            if ("workflow".equals(definition.target().type())) {
                executionId = workflows.run(target, definition.name(), Map.of("prompt", definition.prompt())).getId();
            } else {
                AgentExecuteDTO command = new AgentExecuteDTO();
                command.setPrompt(definition.prompt());
                command.setContext(Map.of("desktopScheduleRunId", claim.runId()));
                executionId = agents.execute(target, command).getExecutionId();
            }
            jdbc.update("UPDATE sf_desktop_schedule_run SET status='running',execution_id=? WHERE id=?",
                    executionId, claim.runId());
            refresh(claim.schedule());
        } catch (RuntimeException ex) {
            finish(claim.schedule(), claim.runId(), "failed", "执行启动失败，请检查目标状态与授权");
        }
    }

    private void refresh(Schedule schedule) {
        for (Run run : jdbc.query("""
                SELECT * FROM sf_desktop_schedule_run WHERE schedule_id=? AND status IN ('submitting','running')
                """, RUN, schedule.id())) {
            if (run.executionId() == null) {
                if (run.startedAt().plusMinutes(15).isBefore(OffsetDateTime.now())) {
                    finish(schedule, run.id(), "failed", "执行确认超时");
                }
                continue;
            }
            String state = executionState(run);
            if (state != null && !ACTIVE.contains(state)) {
                var terminal = Set.of("completed", "success", "succeeded").contains(state) ? "completed" : state;
                finish(schedule, run.id(), terminal, null);
            }
        }
    }

    private String executionState(Run run) {
        if ("workflow".equals(run.executionType())) {
            var instance = workflowInstances.selectById(run.executionId());
            return instance == null ? null : instance.getStatus();
        }
        var execution = agentExecutions.selectById(run.executionId());
        return execution == null ? null : execution.getStatus();
    }

    private void finish(Schedule schedule, String id, String state, String error) {
        int changed = jdbc.update("""
                UPDATE sf_desktop_schedule_run SET status=?,error_summary=?,finished_at=now()
                WHERE id=? AND status IN ('submitting','running')
                """, state, error, id);
        if (changed == 1 && Boolean.TRUE.equals(schedule.definition().notifyInApp())) {
            try {
                notices.createMessage(schedule.tenantId(), schedule.definition().name(),
                        error == null ? "定时任务运行状态：" + state : error,
                        "notification", "desktop_schedule", "desktop_schedule", schedule.id(), null,
                        Map.of("runId", id), List.of(schedule.ownerId()));
            } catch (RuntimeException ex) {
                log.warn("个人调度通知发送失败: scheduleId={}, runId={}", schedule.id(), id);
            }
        }
    }

    private String validateTarget(Definition definition) {
        if (definition.projectId() != null && workspace.project(definition.projectId()).archived()) {
            throw new BusinessException(409, "项目已归档");
        }
        Target target = definition.target();
        return switch (target.type()) {
            case "agent" -> capabilities.requireExpert(target.id(), true).getId();
            case "workflow" -> workflows.requireTemplate(target.id(), true).getId();
            case "workbench" -> {
                var workbench = workspace.workbench(target.id());
                if (workbench.archived()) throw new BusinessException(409, "工作台已归档");
                yield capabilities.requireExpert(workbench.agentId(), true).getId();
            }
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "未知调度目标");
        };
    }

    private OffsetDateTime requireFuture(DesktopScheduleRule rule) {
        var next = rule.next(Instant.now());
        if (next == null) throw new BusinessException(ResultCode.BAD_REQUEST, "请选择未来的执行时间");
        return next;
    }

    private String encode(Definition value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("调度配置无法保存", ex);
        }
    }

    private Definition decode(String value) {
        try {
            return json.readValue(value, Definition.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("调度配置无法读取", ex);
        }
    }
}
