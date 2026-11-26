package com.schemaplexai.service.desktop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.AgentService;
import com.schemaplexai.service.agent.ontology.AgentOntologyRuntimeContext;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DesktopConversationService {
    public static final String CONVERSATION_PREFIX = "desktop-";
    public static final String REQUEST_CONTEXT_KEY = "desktopRequestId";
    private static final Set<String> OPEN = Set.of("queued", "pending", "running", "waiting", "interrupted",
            "waiting_approval", "waiting_for_approval", "pending_approval", "paused");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DesktopWorkspaceService workspace;
    private final DesktopCapabilityService capabilities;
    private final DesktopAttachmentService attachments;
    private final DesktopToolBroker deviceBroker;
    private final AgentService agents;
    private final AgentExecutionMapper executions;
    private final AgentOntologyRuntimeContext ontologyContext;

    public record DeviceRef(@NotBlank @Size(max = 100) String desktopDeviceId,
                            @NotBlank @Size(max = 200) String desktopWorkspaceId) {}
    public record Send(@NotBlank @Size(max = 128) String clientRequestId, @NotBlank @Size(max = 32000) String text,
                       @Size(max = 10) List<@NotBlank @Size(max = 64) String> attachmentIds,
                       @Size(max = 100) String skillCode, @NotNull Long contextVersion, @Valid DeviceRef deviceWorkspaceRef) {}
    public record Receipt(String messageId, String conversationId, String executionId, String status, String eventsUrl) {}
    public record Turn(String id, String prompt, String executionId, String state, long sequence,
                       OffsetDateTime createdAt, Object output, String errorMessage) {}
    private record StoredTurn(String id, String requestId, String fingerprint, String prompt, String executionId,
                              String state, long sequence, OffsetDateTime createdAt) {}
    private static final RowMapper<StoredTurn> TURN = (rs, index) -> new StoredTurn(
            rs.getString("id"), rs.getString("request_id"), rs.getString("fingerprint"), rs.getString("prompt"),
            rs.getString("execution_id"), rs.getString("state"), rs.getLong("sequence"),
            rs.getObject("created_at", OffsetDateTime.class));

    public Receipt send(String id, Send request) {
        Employee employee = Employee.current();
        var conversation = workspace.conversation(id);
        String fingerprint = fingerprint(request);
        StoredTurn previous = stored(id, request.clientRequestId());
        if (previous != null) {
            if (!previous.fingerprint().equals(fingerprint)) {
                throw new BusinessException(409, "请求编号已用于不同消息");
            }
            return receipt(id, conversation.agentId(), reconcile(id, previous));
        }
        if (conversation.archived() || "data".equals(conversation.type())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前会话无法发送工作消息");
        }
        if (conversation.revision() != request.contextVersion()) {
            throw new BusinessException(409, "会话设置已更新，请刷新后发送");
        }
        String agentId = capabilities.requireExpert(conversation.agentId(), true).getId();
        if (StringUtils.hasText(request.skillCode())) capabilities.requireSkillCode(request.skillCode());
        List<String> keys = attachments.objectKeys(request.attachmentIds());
        var context = new java.util.HashMap<String, Object>();
        context.put(REQUEST_CONTEXT_KEY, request.clientRequestId());
        if (request.deviceWorkspaceRef() != null) {
            context.put(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, request.deviceWorkspaceRef().desktopDeviceId());
            context.put(DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, request.deviceWorkspaceRef().desktopWorkspaceId());
        }
        deviceBroker.validateTarget(employee.tenantId(), employee.userId(), DesktopExecutionTarget.from(context));
        jdbc.query("""
                SELECT * FROM sf_desktop_turn WHERE conversation_id=?
                  AND state IN ('submitting','queued','running')
                """, TURN, id).forEach(turn -> reconcile(id, turn));
        String messageId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO sf_desktop_turn(id,conversation_id,request_id,fingerprint,prompt,attachment_ids,state)
                    VALUES(?,?,?,?,?,CAST(? AS jsonb),'submitting')
                    """, messageId, id, request.clientRequestId(), fingerprint, request.text().strip(),
                    jsonValue(request.attachmentIds() == null ? List.of() : request.attachmentIds()));
        } catch (DuplicateKeyException duplicate) {
            StoredTurn concurrent = stored(id, request.clientRequestId());
            if (concurrent != null && fingerprint.equals(concurrent.fingerprint())) {
                return receipt(id, agentId, reconcile(id, concurrent));
            }
            throw new BusinessException(409, "会话已有执行中的请求，请查看执行状态");
        }
        AgentExecuteDTO command = new AgentExecuteDTO();
        command.setConversationId(id);
        command.setPrompt(request.text().strip());
        command.setAttachmentIds(keys);
        command.setSkillCode(request.skillCode());
        command.setContext(context);
        command.setStream(true);
        try {
            var result = agents.execute(agentId, command);
            recordExecution(messageId, result.getExecutionId(), settledState(result.getStatus()));
            workspace.activateConversation(id, command.getPrompt());
        } catch (RuntimeException failure) {
            StoredTurn recovered = reconcile(id, stored(id, request.clientRequestId()));
            if (recovered.executionId() == null) {
                recordExecution(messageId, null, "failed");
                throw failure;
            }
            workspace.activateConversation(id, command.getPrompt());
        }
        return receipt(id, agentId, stored(id, request.clientRequestId()));
    }

    public PageResult<Turn> messages(String id, int page, int size) {
        workspace.conversation(id);
        long total = jdbc.queryForObject("SELECT count(*) FROM sf_desktop_turn WHERE conversation_id=?",
                Long.class, id);
        var rows = jdbc.query("""
                SELECT * FROM sf_desktop_turn WHERE conversation_id=? ORDER BY sequence DESC LIMIT ? OFFSET ?
                """, TURN, id, size, DesktopWorkspaceService.offset(page, size));
        var turns = rows.stream().map(row -> toTurn(id, row)).toList();
        return new PageResult<>(turns, total, page, size);
    }

    private Turn toTurn(String id, StoredTurn previous) {
        StoredTurn row = reconcile(id, previous);
        AgentExecution execution = execution(id, row);
        boolean showOutput = execution != null && (!Boolean.TRUE.equals(execution.getOntologyPrivate())
                || ontologyContext.mayReadCurrentUser());
        return new Turn(row.id(), row.prompt(), row.executionId(), execution == null ? row.state() : execution.getStatus(),
                row.sequence(), row.createdAt(), showOutput ? execution.getOutputResult() : null,
                showOutput ? execution.getErrorMessage() : null);
    }

    private StoredTurn reconcile(String id, StoredTurn row) {
        if (row == null) throw new BusinessException(ResultCode.NOT_FOUND);
        var execution = execution(id, row);
        if (execution != null) {
            String state = settledState(execution.getStatus());
            if (!state.equals(row.state()) || row.executionId() == null) {
                recordExecution(row.id(), execution.getId(), state);
                if (row.executionId() == null) workspace.activateConversation(id, row.prompt());
                return new StoredTurn(row.id(), row.requestId(), row.fingerprint(), row.prompt(), execution.getId(),
                        state, row.sequence(), row.createdAt());
            }
        } else if ("submitting".equals(row.state()) && row.createdAt().plusMinutes(5).isBefore(OffsetDateTime.now())) {
            recordExecution(row.id(), null, "failed");
            return stored(id, row.requestId());
        }
        return row;
    }

    private AgentExecution execution(String id, StoredTurn row) {
        Employee employee = Employee.current();
        var query = new LambdaQueryWrapper<AgentExecution>()
                .eq(AgentExecution::getTenantId, employee.tenantId())
                .eq(AgentExecution::getCreatedBy, employee.userId())
                // Solo 执行记录可能已改用带租户前缀的记忆会话 ID
                .in(AgentExecution::getConversationId, id, employee.tenantId() + ":" + id);
        if (row.executionId() != null) {
            query.eq(AgentExecution::getId, row.executionId());
        } else {
            query.apply("input_context->>'desktopRequestId' = {0}", row.requestId());
        }
        return executions.selectList(query.orderByDesc(AgentExecution::getCreatedAt).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private StoredTurn stored(String id, String requestId) {
        return jdbc.query("SELECT * FROM sf_desktop_turn WHERE conversation_id=? AND request_id=?",
                TURN, id, requestId).stream().findFirst().orElse(null);
    }

    private void recordExecution(String id, String executionId, String state) {
        jdbc.update("UPDATE sf_desktop_turn SET execution_id=?, state=? WHERE id=?", executionId, state, id);
    }

    private static String settledState(String status) {
        return status != null && OPEN.contains(status.toLowerCase()) ? "running" : "finished";
    }

    private Receipt receipt(String id, String agentId, StoredTurn row) {
        String events = row.executionId() == null ? null
                : "/agents/" + agentId + "/executions/" + row.executionId() + "/events";
        return new Receipt(row.id(), id, row.executionId(), row.state(), events);
    }

    private String fingerprint(Send request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(jsonValue(request).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String jsonValue(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法解析消息");
        }
    }
}
