package com.schemaplexai.service.homepage.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.homepage.AuditTrailVO;
import com.schemaplexai.model.vo.homepage.BlockChainVO;
import com.schemaplexai.model.vo.homepage.ContextSummaryVO;
import com.schemaplexai.model.vo.homepage.HomepageAggregateVO;
import com.schemaplexai.model.vo.homepage.QuickEntryVO;
import com.schemaplexai.model.vo.homepage.RecommendedActionVO;
import com.schemaplexai.model.vo.homepage.RiskAlertVO;
import com.schemaplexai.model.vo.homepage.SituationPanelVO;
import com.schemaplexai.model.vo.homepage.TaskItemVO;
import com.schemaplexai.model.vo.homepage.TaskPanelVO;
import com.schemaplexai.model.vo.homepage.TrendSummaryVO;
import com.schemaplexai.service.homepage.HomepageAggregateService;
import com.schemaplexai.service.monitor.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首页聚合服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomepageAggregateServiceImpl implements HomepageAggregateService {

    private static final Set<String> RUNNING_STATUSES = Set.of(
            WorkflowInstanceStatusEnum.RUNNING.getCode(),
            WorkflowInstanceStatusEnum.PENDING.getCode()
    );
    private static final Set<String> BLOCKED_STATUSES = Set.of(
            WorkflowInstanceStatusEnum.PAUSED.getCode(),
            WorkflowInstanceStatusEnum.FAILED.getCode()
    );
    private static final Set<String> ACTIVE_INCIDENT_STATUSES = Set.of(
            "new", "assigned", "investigating", "escalated"
    );
    private static final Set<String> PRIORITY_AUDIT_RESOURCES = Set.of(
            "agent_execution", "workflow_instance", "workflow_node", "security_incident", "review_session"
    );
    private static final Pattern JSON_STRING_FIELD_PATTERN = Pattern.compile("\"%s\"\\s*:\\s*\"([^\"]*)\"");

    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final QualityDeviationMapper qualityDeviationMapper;
    private final SecurityIncidentMapper securityIncidentMapper;
    private final AuditLogMapper auditLogMapper;
    private final DashboardService dashboardService;

    @Override
    public HomepageAggregateVO aggregate(String mode) {
        log.info("聚合首页数据, mode={}", mode);

        HomepageAggregateVO vo = new HomepageAggregateVO();

        ContextSummaryVO summary = buildContextSummary();
        vo.setContextSummary(summary);
        vo.setActions(buildRecommendedActions(summary));

        if (mode == null || "tasks".equals(mode) || "ai".equals(mode)) {
            vo.setTaskPanel(buildTaskPanel());
        }
        if (mode == null || "situation".equals(mode)) {
            vo.setSituationPanel(buildSituationPanel());
        }

        return vo;
    }

    private ContextSummaryVO buildContextSummary() {
        ContextSummaryVO summary = new ContextSummaryVO();

        Long pendingCount = workflowNodeExecutionMapper.selectCount(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getStatus, "PENDING_REVIEW"));
        summary.setPendingApprovalCount(pendingCount != null ? pendingCount.intValue() : 0);

        Long blockedCount = workflowInstanceMapper.selectCount(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, BLOCKED_STATUSES));
        summary.setBlockedWorkflowCount(blockedCount != null ? blockedCount.intValue() : 0);

        Long runningCount = workflowInstanceMapper.selectCount(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, RUNNING_STATUSES));
        summary.setRunningWorkflowCount(runningCount != null ? runningCount.intValue() : 0);

        Long securityCount = securityIncidentMapper.selectCount(
                new LambdaQueryWrapper<SecurityIncident>()
                        .in(SecurityIncident::getStatus, ACTIVE_INCIDENT_STATUSES));
        summary.setSecurityEventCount(securityCount != null ? securityCount.intValue() : 0);

        Long deviationCount = qualityDeviationMapper.selectCount(
                new LambdaQueryWrapper<QualityDeviation>()
                        .in(QualityDeviation::getStatus, List.of("open", "acknowledged")));
        summary.setQualityDeviationCount(deviationCount != null ? deviationCount.intValue() : 0);

        summary.setSummaryText(generateSummaryText(summary));
        return summary;
    }

    private String generateSummaryText(ContextSummaryVO s) {
        List<String> parts = new ArrayList<>();
        if (s.getPendingApprovalCount() > 0) {
            parts.add(s.getPendingApprovalCount() + " 个待审批");
        }
        if (s.getBlockedWorkflowCount() > 0) {
            parts.add(s.getBlockedWorkflowCount() + " 个被阻塞的流程");
        }
        if (s.getRunningWorkflowCount() > 0) {
            parts.add(s.getRunningWorkflowCount() + " 个运行中的工作流");
        }
        if (s.getSecurityEventCount() > 0) {
            parts.add(s.getSecurityEventCount() + " 个安全事件");
        }
        if (s.getQualityDeviationCount() > 0) {
            parts.add(s.getQualityDeviationCount() + " 个质量偏差");
        }
        if (parts.isEmpty()) {
            return "当前没有需要关注的事项，一切运行正常";
        }
        return "你有 " + String.join("、", parts);
    }

    private List<RecommendedActionVO> buildRecommendedActions(ContextSummaryVO summary) {
        List<RecommendedActionVO> actions = new ArrayList<>();
        int priority = 1;

        if (summary.getBlockedWorkflowCount() > 0) {
            RecommendedActionVO action = new RecommendedActionVO();
            action.setActionId(UUID.randomUUID().toString());
            action.setActionType("UNBLOCK");
            action.setTitle("处理 " + summary.getBlockedWorkflowCount() + " 个被阻塞的流程");
            action.setTargetUrl("/workflow/executions?status=blocked");
            action.setPriority(priority++);
            actions.add(action);
        }

        if (summary.getSecurityEventCount() > 0) {
            RecommendedActionVO action = new RecommendedActionVO();
            action.setActionId(UUID.randomUUID().toString());
            action.setActionType("INVESTIGATE");
            action.setTitle("处理 " + summary.getSecurityEventCount() + " 个安全事件");
            action.setTargetUrl("/security-compliance/overview");
            action.setPriority(priority++);
            actions.add(action);
        }

        if (summary.getPendingApprovalCount() > 0) {
            RecommendedActionVO action = new RecommendedActionVO();
            action.setActionId(UUID.randomUUID().toString());
            action.setActionType("APPROVE");
            action.setTitle("审批 " + summary.getPendingApprovalCount() + " 个待处理评审");
            action.setTargetUrl("/approval-center?view=pending");
            action.setPriority(priority++);
            actions.add(action);
        }

        if (summary.getRunningWorkflowCount() > 0) {
            RecommendedActionVO action = new RecommendedActionVO();
            action.setActionId(UUID.randomUUID().toString());
            action.setActionType("CONTINUE");
            action.setTitle("继续跟进 " + summary.getRunningWorkflowCount() + " 个运行中的工作流");
            action.setTargetUrl("/workflow/executions?status=running");
            action.setPriority(priority++);
            actions.add(action);
        }

        if (summary.getQualityDeviationCount() > 0) {
            RecommendedActionVO action = new RecommendedActionVO();
            action.setActionId(UUID.randomUUID().toString());
            action.setActionType("INVESTIGATE");
            action.setTitle("处理 " + summary.getQualityDeviationCount() + " 个质量偏差");
            action.setTargetUrl("/quality/deviations");
            action.setPriority(priority++);
            actions.add(action);
        }

        return actions.size() > 4 ? actions.subList(0, 4) : actions;
    }

    private TaskPanelVO buildTaskPanel() {
        TaskPanelVO panel = new TaskPanelVO();

        // 进行中任务
        List<WorkflowInstance> running = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, RUNNING_STATUSES)
                        .orderByDesc(WorkflowInstance::getUpdatedAt)
                        .last("LIMIT 10"));
        panel.setInProgressTasks(sortTaskItems(running.stream()
                .map(wf -> toWorkflowTaskItem(wf, false))
                .toList()));

        // 阻塞任务
        List<WorkflowInstance> blocked = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, BLOCKED_STATUSES)
                        .orderByDesc(WorkflowInstance::getUpdatedAt)
                        .last("LIMIT 10"));
        panel.setBlockedTasks(sortTaskItems(blocked.stream()
                .map(wf -> toWorkflowTaskItem(wf, true))
                .toList()));

        // 待审批
        List<WorkflowNodeExecution> pending = workflowNodeExecutionMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getStatus, "PENDING_REVIEW")
                        .orderByDesc(WorkflowNodeExecution::getCreatedAt)
                        .last("LIMIT 10"));
        panel.setPendingApprovals(sortTaskItems(pending.stream()
                .map(this::toPendingApprovalTaskItem)
                .toList()));

        // 快捷入口
        panel.setQuickEntries(buildQuickEntries());
        return panel;
    }

    private TaskItemVO toWorkflowTaskItem(WorkflowInstance workflow, boolean blocked) {
        Map<String, Object> variables = workflow.getVariables() == null ? Map.of() : workflow.getVariables();
        String ownerUserId = firstTextValue(variables, "ownerUserId", "assigneeId", "responsibleUserId");
        if (!StringUtils.hasText(ownerUserId)) {
            ownerUserId = StringUtils.hasText(workflow.getUpdatedBy()) ? workflow.getUpdatedBy() : workflow.getCreatedBy();
        }
        TaskItemVO item = new TaskItemVO();
        item.setId(workflow.getId());
        item.setItemType("workflow");
        item.setTitle(workflow.getName());
        item.setStatus(workflow.getStatus());
        item.setSeverity(blocked ? "HIGH" : stringValue(variables.get("severity")));
        item.setTargetUrl("/workflow/" + workflow.getId());
        item.setUpdatedAt(workflow.getUpdatedAt() != null ? workflow.getUpdatedAt().toString() : null);
        item.setOwnerUserId(ownerUserId);
        item.setOwnerRole(defaultString(firstTextValue(variables, "ownerRole", "role"), "流程负责人"));
        item.setSlaDueAt(stringValue(variables.get("slaDueAt")));
        item.setImpactScope(stringValue(variables.get("impactScope")));
        item.setEvidenceCount(intValue(variables.get("evidenceCount"), blocked ? 1 : 0));
        item.setRequiresAction(isCurrentUser(ownerUserId) || blocked);
        return item;
    }

    private TaskItemVO toPendingApprovalTaskItem(WorkflowNodeExecution node) {
        Map<String, Object> input = node.getInputData() == null ? Map.of() : node.getInputData();
        String ownerUserId = defaultString(firstTextValue(input, "ownerUserId", "reviewerId", "assigneeId"),
                SecurityUtil.getCurrentUserId());
        TaskItemVO item = new TaskItemVO();
        item.setId(node.getId());
        item.setItemType("approval");
        item.setTitle("审批: " + node.getNodeLabel());
        item.setStatus("PENDING_REVIEW");
        item.setTargetUrl(StringUtils.hasText(node.getActionUrl()) ? node.getActionUrl() : "/approval-center?view=pending");
        item.setUpdatedAt(node.getCreatedAt() != null ? node.getCreatedAt().toString() : null);
        item.setOwnerUserId(ownerUserId);
        item.setOwnerRole(defaultString(firstTextValue(input, "ownerRole", "role"), "审批人"));
        item.setSlaDueAt(stringValue(input.get("slaDueAt")));
        item.setImpactScope(stringValue(input.get("impactScope")));
        item.setEvidenceCount(intValue(input.get("evidenceCount"), 1));
        item.setRequiresAction(Boolean.TRUE);
        return item;
    }

    private List<TaskItemVO> sortTaskItems(List<TaskItemVO> items) {
        return items.stream()
                .sorted(Comparator
                        .comparing((TaskItemVO item) -> Boolean.TRUE.equals(item.getRequiresAction())).reversed()
                        .thenComparing(item -> severityRank(item.getSeverity()), Comparator.reverseOrder())
                        .thenComparing(TaskItemVO::getSlaDueAt, Comparator.nullsLast(String::compareTo))
                        .thenComparing(TaskItemVO::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private int severityRank(String severity) {
        return switch (defaultString(severity, "").toUpperCase()) {
            case "CRITICAL" -> 4;
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private boolean isCurrentUser(String userId) {
        return StringUtils.hasText(userId) && userId.equals(SecurityUtil.getCurrentUserId());
    }

    private String firstTextValue(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            String value = stringValue(data.get(key));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private Integer intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<QuickEntryVO> buildQuickEntries() {
        List<QuickEntryVO> entries = new ArrayList<>();

        QuickEntryVO createAgent = new QuickEntryVO();
        createAgent.setId("quick-create-agent");
        createAgent.setLabel("创建 Agent");
        createAgent.setIcon("RobotOutlined");
        createAgent.setTargetUrl("/agent/create");
        entries.add(createAgent);

        QuickEntryVO createWorkflow = new QuickEntryVO();
        createWorkflow.setId("quick-create-workflow");
        createWorkflow.setLabel("创建工作流");
        createWorkflow.setIcon("ApartmentOutlined");
        createWorkflow.setTargetUrl("/workflow/create");
        entries.add(createWorkflow);

        QuickEntryVO writeSpec = new QuickEntryVO();
        writeSpec.setId("quick-write-spec");
        writeSpec.setLabel("编写 Spec");
        writeSpec.setIcon("FileTextOutlined");
        writeSpec.setTargetUrl("/spec/create");
        entries.add(writeSpec);

        QuickEntryVO viewMonitor = new QuickEntryVO();
        viewMonitor.setId("quick-view-monitor");
        viewMonitor.setLabel("查看监控");
        viewMonitor.setIcon("BarChartOutlined");
        viewMonitor.setTargetUrl("/monitor");
        entries.add(viewMonitor);

        return entries;
    }

    private SituationPanelVO buildSituationPanel() {
        SituationPanelVO panel = new SituationPanelVO();

        panel.setSystemHealth(dashboardService.getSystemHealth());

        // 风险告警：安全事件 + 质量偏差
        List<RiskAlertVO> alerts = new ArrayList<>();
        List<SecurityIncident> incidents = securityIncidentMapper.selectList(
                new LambdaQueryWrapper<SecurityIncident>()
                        .in(SecurityIncident::getStatus, ACTIVE_INCIDENT_STATUSES)
                        .orderByDesc(SecurityIncident::getCreatedAt)
                        .last("LIMIT 5"));
        for (SecurityIncident inc : incidents) {
            RiskAlertVO alert = new RiskAlertVO();
            alert.setId(inc.getId());
            alert.setAlertType("security");
            alert.setSeverity(inc.getRiskLevel() != null ? inc.getRiskLevel().toUpperCase() : "MEDIUM");
            alert.setTitle(inc.getEventTitle());
            alert.setDescription(inc.getEventDetail());
            alert.setTargetUrl("/security-compliance/overview");
            alert.setCreatedAt(inc.getCreatedAt() != null ? inc.getCreatedAt().toString() : null);
            alerts.add(alert);
        }
        panel.setRiskAlerts(alerts);

        // 阻塞链路
        List<BlockChainVO> chains = new ArrayList<>();
        List<WorkflowInstance> blockedWfs = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, BLOCKED_STATUSES)
                        .orderByDesc(WorkflowInstance::getUpdatedAt)
                        .last("LIMIT 5"));
        for (WorkflowInstance wf : blockedWfs) {
            BlockChainVO chain = new BlockChainVO();
            chain.setId(wf.getId());
            chain.setBlockerType("workflow");
            chain.setBlockerTitle(wf.getName());
            chain.setBlockedResource("工作流执行");
            chain.setStatus(wf.getStatus());
            chain.setTargetUrl("/workflow/" + wf.getId());
            chains.add(chain);
        }
        panel.setBlockChains(chains);

        panel.setRecentAuditTrails(buildRecentAuditTrails());

        // 异常趋势
        panel.setAnomalyTrend(buildAnomalyTrend());

        return panel;
    }

    private TrendSummaryVO buildAnomalyTrend() {
        TrendSummaryVO trend = new TrendSummaryVO();
        List<Integer> counts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");

        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            labels.add(date.format(fmt));
            LocalDateTime dayStart = date.atStartOfDay();
            LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();

            Long count = qualityDeviationMapper.selectCount(
                    new LambdaQueryWrapper<QualityDeviation>()
                            .ge(QualityDeviation::getCreatedAt, dayStart)
                            .lt(QualityDeviation::getCreatedAt, dayEnd));
            counts.add(count != null ? count.intValue() : 0);
        }

        trend.setDailyCounts(counts);
        trend.setLabels(labels);
        trend.setTotalCount(counts.stream().mapToInt(Integer::intValue).sum());

        if (counts.size() >= 2) {
            int last = counts.get(counts.size() - 1);
            int prev = counts.get(counts.size() - 2);
            trend.setTrend(last > prev ? "up" : last < prev ? "down" : "stable");
        } else {
            trend.setTrend("stable");
        }

        return trend;
    }

    private List<AuditTrailVO> buildRecentAuditTrails() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            return new ArrayList<>();
        }
        List<AuditLog> logs = auditLogMapper.selectList(new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, tenantId)
                .and(wrapper -> wrapper.in(AuditLog::getResource, PRIORITY_AUDIT_RESOURCES)
                        .or()
                        .like(AuditLog::getAction, "budget.")
                        .or()
                        .like(AuditLog::getAction, "approval.")
                        .or()
                        .like(AuditLog::getAction, "workflow.")
                        .or()
                        .like(AuditLog::getAction, "security."))
                .orderByDesc(AuditLog::getCreatedAt)
                .last("LIMIT 10"));
        return logs.stream().map(this::toAuditTrail).toList();
    }

    private AuditTrailVO toAuditTrail(AuditLog log) {
        AuditTrailVO trail = new AuditTrailVO();
        trail.setId(log.getId());
        trail.setUsername(StringUtils.hasText(log.getUsername()) ? log.getUsername() : "系统");
        trail.setAction(log.getAction());
        trail.setResource(log.getResource());
        trail.setResourceId(log.getResourceId());
        trail.setDetail(resolveAuditMessage(log));
        trail.setSeverity(resolveAuditSeverity(log));
        trail.setTargetUrl(resolveAuditTargetUrl(log));
        trail.setTraceId(extractJsonString(log.getDetail(), "traceId"));
        trail.setCreatedAt(log.getCreatedAt() != null ? log.getCreatedAt().toString() : null);
        return trail;
    }

    private String resolveAuditMessage(AuditLog log) {
        String message = extractJsonString(log.getDetail(), "message");
        if (StringUtils.hasText(message)) {
            return message;
        }
        return log.getDetail();
    }

    private String resolveAuditSeverity(AuditLog log) {
        String severity = extractJsonString(log.getDetail(), "severity");
        if (StringUtils.hasText(severity)) {
            return severity.toUpperCase();
        }
        String action = log.getAction() == null ? "" : log.getAction().toLowerCase();
        if (action.contains("block") || action.contains("denied") || action.contains("failed")) {
            return "HIGH";
        }
        if (action.contains("warn") || action.contains("downgrade") || action.contains("security")) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String resolveAuditTargetUrl(AuditLog log) {
        String resourceId = log.getResourceId();
        String resource = log.getResource();
        if (!StringUtils.hasText(resourceId)) {
            return "/dashboard?mode=situation";
        }
        if ("agent_execution".equals(resource)) {
            return "/agents/executions/" + resourceId;
        }
        if ("workflow_instance".equals(resource)) {
            return "/workflow/" + resourceId;
        }
        if ("workflow_node".equals(resource)) {
            return "/workflow?nodeExecutionId=" + resourceId;
        }
        if ("security_incident".equals(resource)) {
            return "/security-compliance/overview?incidentId=" + resourceId;
        }
        if ("review_session".equals(resource)) {
            return "/approval-center?reviewSessionId=" + resourceId;
        }
        return "/dashboard?mode=situation";
    }

    private String extractJsonString(String raw, String field) {
        if (!StringUtils.hasText(raw) || !StringUtils.hasText(field)) {
            return null;
        }
        Matcher matcher = Pattern.compile(String.format(JSON_STRING_FIELD_PATTERN.pattern(), Pattern.quote(field))).matcher(raw);
        return matcher.find() ? matcher.group(1) : null;
    }
}
