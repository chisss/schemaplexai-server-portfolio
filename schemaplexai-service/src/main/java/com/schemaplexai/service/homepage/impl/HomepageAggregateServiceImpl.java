package com.schemaplexai.service.homepage.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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

    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final QualityDeviationMapper qualityDeviationMapper;
    private final SecurityIncidentMapper securityIncidentMapper;
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
            action.setTargetUrl("/security/incidents");
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
        panel.setInProgressTasks(running.stream().map(wf -> {
            TaskItemVO item = new TaskItemVO();
            item.setId(wf.getId());
            item.setItemType("workflow");
            item.setTitle(wf.getName());
            item.setStatus(wf.getStatus());
            item.setTargetUrl("/workflow/executions/" + wf.getId());
            item.setUpdatedAt(wf.getUpdatedAt() != null ? wf.getUpdatedAt().toString() : null);
            return item;
        }).toList());

        // 阻塞任务
        List<WorkflowInstance> blocked = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, BLOCKED_STATUSES)
                        .orderByDesc(WorkflowInstance::getUpdatedAt)
                        .last("LIMIT 10"));
        panel.setBlockedTasks(blocked.stream().map(wf -> {
            TaskItemVO item = new TaskItemVO();
            item.setId(wf.getId());
            item.setItemType("workflow");
            item.setTitle(wf.getName());
            item.setStatus(wf.getStatus());
            item.setSeverity("HIGH");
            item.setTargetUrl("/workflow/executions/" + wf.getId());
            item.setUpdatedAt(wf.getUpdatedAt() != null ? wf.getUpdatedAt().toString() : null);
            return item;
        }).toList());

        // 待审批
        List<WorkflowNodeExecution> pending = workflowNodeExecutionMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getStatus, "PENDING_REVIEW")
                        .orderByDesc(WorkflowNodeExecution::getCreatedAt)
                        .last("LIMIT 10"));
        panel.setPendingApprovals(pending.stream().map(node -> {
            TaskItemVO item = new TaskItemVO();
            item.setId(node.getId());
            item.setItemType("approval");
            item.setTitle("审批: " + node.getNodeLabel());
            item.setStatus("PENDING_REVIEW");
            item.setTargetUrl("/approval-center?view=pending");
            item.setUpdatedAt(node.getCreatedAt() != null ? node.getCreatedAt().toString() : null);
            return item;
        }).toList());

        // 快捷入口
        panel.setQuickEntries(buildQuickEntries());
        return panel;
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
            alert.setTargetUrl("/security/incidents/" + inc.getId());
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
            chain.setTargetUrl("/workflow/executions/" + wf.getId());
            chains.add(chain);
        }
        panel.setBlockChains(chains);

        // 审计追踪（简化：返回空列表，后续可接入 AuditLogService）
        panel.setRecentAuditTrails(new ArrayList<>());

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
}
