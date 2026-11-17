package com.schemaplexai.service.homepage;

import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.monitor.SystemHealthVO;
import com.schemaplexai.service.homepage.impl.HomepageAggregateServiceImpl;
import com.schemaplexai.service.monitor.DashboardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HomepageAggregateServiceTest {

    private final WorkflowInstanceMapper workflowInstanceMapper = mock(WorkflowInstanceMapper.class);
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
    private final AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
    private final QualityDeviationMapper qualityDeviationMapper = mock(QualityDeviationMapper.class);
    private final SecurityIncidentMapper securityIncidentMapper = mock(SecurityIncidentMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final DashboardService dashboardService = mock(DashboardService.class);
    private final HomepageAggregateService service = new HomepageAggregateServiceImpl(
            workflowInstanceMapper,
            workflowNodeExecutionMapper,
            agentExecutionMapper,
            qualityDeviationMapper,
            securityIncidentMapper,
            auditLogMapper,
            dashboardService
    );

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void shouldExposeRecentAuditTrailsWithEvidenceFields() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        when(workflowNodeExecutionMapper.selectCount(any())).thenReturn(0L);
        when(workflowInstanceMapper.selectCount(any())).thenReturn(0L);
        when(securityIncidentMapper.selectCount(any())).thenReturn(0L);
        when(qualityDeviationMapper.selectCount(any())).thenReturn(0L);
        when(workflowInstanceMapper.selectList(any())).thenReturn(List.of());
        when(securityIncidentMapper.selectList(any())).thenReturn(List.of());
        when(dashboardService.getSystemHealth()).thenReturn(systemHealth());
        when(auditLogMapper.selectList(any())).thenReturn(List.of(auditLog()));

        var aggregate = service.aggregate("situation");

        assertThat(aggregate.getSituationPanel().getRecentAuditTrails()).hasSize(1);
        var trail = aggregate.getSituationPanel().getRecentAuditTrails().get(0);
        assertThat(trail.getResourceId()).isEqualTo("execution-1");
        assertThat(trail.getSeverity()).isEqualTo("HIGH");
        assertThat(trail.getTargetUrl()).isEqualTo("/agents/executions/execution-1");
        assertThat(trail.getTraceId()).isEqualTo("trace-1");
    }

    @Test
    void shouldExposeRoleTaskMetadataAndPrioritizeActionableItems() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        SecurityUtil.setCurrentUserId("user-1");
        when(workflowNodeExecutionMapper.selectCount(any())).thenReturn(1L);
        when(workflowInstanceMapper.selectCount(any())).thenReturn(2L);
        when(securityIncidentMapper.selectCount(any())).thenReturn(0L);
        when(qualityDeviationMapper.selectCount(any())).thenReturn(0L);
        when(workflowInstanceMapper.selectList(any()))
                .thenReturn(List.of(
                        workflow("wf-other", "他人运行", "running", "user-2", LocalDateTime.now().minusMinutes(2), Map.of(
                                "ownerRole", "平台运营",
                                "slaDueAt", "2026-04-29T13:00:00",
                                "impactScope", "后台批处理"
                        )),
                        workflow("wf-mine", "我负责的运行", "running", "user-1", LocalDateTime.now().minusHours(1), Map.of(
                                "ownerRole", "流程负责人",
                                "slaDueAt", "2026-04-29T12:00:00",
                                "impactScope", "客户交付",
                                "evidenceCount", 2
                        ))))
                .thenReturn(List.of(
                        workflow("wf-blocked", "我负责的阻塞", "paused", "user-1", LocalDateTime.now().minusHours(2), Map.of(
                                "ownerRole", "流程负责人",
                                "slaDueAt", "2026-04-29T11:30:00",
                                "impactScope", "生产执行链",
                                "evidenceCount", 3
                        ))));
        when(workflowNodeExecutionMapper.selectList(any())).thenReturn(List.of(pendingApproval()));

        var aggregate = service.aggregate("tasks");

        assertThat(aggregate.getTaskPanel().getPendingApprovals()).singleElement().satisfies(item -> {
            assertThat(item.getOwnerUserId()).isEqualTo("user-1");
            assertThat(item.getOwnerRole()).isEqualTo("审批人");
            assertThat(item.getSlaDueAt()).isEqualTo("2026-04-29T10:30:00");
            assertThat(item.getImpactScope()).isEqualTo("上线审批");
            assertThat(item.getEvidenceCount()).isEqualTo(1);
            assertThat(item.getRequiresAction()).isTrue();
        });
        assertThat(aggregate.getTaskPanel().getInProgressTasks()).extracting("id")
                .containsExactly("wf-mine", "wf-other");
        assertThat(aggregate.getTaskPanel().getBlockedTasks()).singleElement().satisfies(item -> {
            assertThat(item.getOwnerUserId()).isEqualTo("user-1");
            assertThat(item.getOwnerRole()).isEqualTo("流程负责人");
            assertThat(item.getImpactScope()).isEqualTo("生产执行链");
            assertThat(item.getEvidenceCount()).isEqualTo(3);
            assertThat(item.getRequiresAction()).isTrue();
        });
    }

    private SystemHealthVO systemHealth() {
        SystemHealthVO health = new SystemHealthVO();
        health.setStatus("healthy");
        health.setCpuUsage(0D);
        health.setMemoryUsage(0D);
        health.setDiskUsage(0D);
        health.setServiceStatus(Map.of());
        return health;
    }

    private AuditLog auditLog() {
        AuditLog log = new AuditLog();
        log.setId("audit-1");
        log.setTenantId("tenant-1");
        log.setUsername("Alice");
        log.setAction("budget.block");
        log.setResource("agent_execution");
        log.setResourceId("execution-1");
        log.setDetail("{\"severity\":\"HIGH\",\"traceId\":\"trace-1\",\"message\":\"预算阻断\"}");
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    private WorkflowInstance workflow(String id,
                                      String name,
                                      String status,
                                      String ownerUserId,
                                      LocalDateTime updatedAt,
                                      Map<String, Object> variables) {
        WorkflowInstance workflow = new WorkflowInstance();
        workflow.setId(id);
        workflow.setTenantId("tenant-1");
        workflow.setName(name);
        workflow.setStatus(status);
        workflow.setCreatedBy(ownerUserId);
        workflow.setUpdatedBy(ownerUserId);
        workflow.setVariables(variables);
        workflow.setUpdatedAt(updatedAt);
        return workflow;
    }

    private WorkflowNodeExecution pendingApproval() {
        WorkflowNodeExecution node = new WorkflowNodeExecution();
        node.setId("approval-1");
        node.setInstanceId("wf-1");
        node.setNodeId("review-1");
        node.setNodeLabel("架构审批");
        node.setStatus("PENDING_REVIEW");
        node.setInputData(Map.of(
                "ownerUserId", "user-1",
                "ownerRole", "审批人",
                "slaDueAt", "2026-04-29T10:30:00",
                "impactScope", "上线审批",
                "evidenceCount", 1
        ));
        node.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        return node;
    }
}
