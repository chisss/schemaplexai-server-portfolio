package com.schemaplexai.service.cost;

import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.dao.mapper.BudgetMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.model.entity.Budget;
import com.schemaplexai.service.cost.impl.BudgetAdmissionServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetAdmissionServiceTest {

    private final BudgetMapper budgetMapper = mock(BudgetMapper.class);
    private final AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
    private final AiModelMapper aiModelMapper = mock(AiModelMapper.class);
    private final SpecMapper specMapper = mock(SpecMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final BudgetAdmissionService service = new BudgetAdmissionServiceImpl(
            budgetMapper,
            agentExecutionMapper,
            aiModelMapper,
            specMapper,
            auditLogMapper
    );

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void shouldAllowExecutionWhenBudgetHasRemainingCapacity() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        SecurityUtil.setCurrentUserId("user-1");
        when(budgetMapper.selectList(any())).thenReturn(List.of(activeBudget("budget-1", "alert", "100.00")));
        when(agentExecutionMapper.selectList(any())).thenReturn(List.of(execution("model-a", 100L)));
        when(aiModelMapper.selectList(any())).thenReturn(List.of(model("model-a", "100.00")));

        var admission = service.evaluateForAgentExecution("agent-1", null, "user-1", "model-a");

        assertThat(admission.getAllowed()).isTrue();
        assertThat(admission.getStrategy()).isEqualTo("allow");
        assertThat(admission.getBudgetId()).isEqualTo("budget-1");
        assertThat(admission.getUsageRate()).isEqualTo(10D);
        assertThat(admission.getActionRequired()).isFalse();
    }

    @Test
    void shouldBlockExecutionWhenBlockBudgetIsOverLimit() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        SecurityUtil.setCurrentUserId("user-1");
        when(budgetMapper.selectList(any())).thenReturn(List.of(activeBudget("budget-1", "block", "100.00")));
        when(agentExecutionMapper.selectList(any())).thenReturn(List.of(execution("model-a", 1100L)));
        when(aiModelMapper.selectList(any())).thenReturn(List.of(model("model-a", "100.00")));

        var admission = service.evaluateForAgentExecution("agent-1", null, "user-1", "model-a");

        assertThat(admission.getAllowed()).isFalse();
        assertThat(admission.getStrategy()).isEqualTo("block");
        assertThat(admission.getMessage()).contains("预算");
        assertThat(admission.getActionRequired()).isTrue();
    }

    @Test
    void shouldAllowAndAlertWhenAlertBudgetIsOverLimit() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        SecurityUtil.setCurrentUserId("user-1");
        when(budgetMapper.selectList(any())).thenReturn(List.of(activeBudget("budget-1", "alert", "100.00")));
        when(agentExecutionMapper.selectList(any())).thenReturn(List.of(execution("model-a", 1100L)));
        when(aiModelMapper.selectList(any())).thenReturn(List.of(model("model-a", "100.00")));

        var admission = service.evaluateForAgentExecution("agent-1", null, "user-1", "model-a");

        assertThat(admission.getAllowed()).isTrue();
        assertThat(admission.getStrategy()).isEqualTo("alert");
        assertThat(admission.getMessage()).contains("预算");
        assertThat(admission.getActionRequired()).isTrue();
    }

    @Test
    void shouldRecordAuditForAdmissionDecision() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        SecurityUtil.setCurrentUserId("user-1");
        SecurityUtil.setCurrentUsername("Alice");
        when(budgetMapper.selectList(any())).thenReturn(List.of(activeBudget("budget-1", "downgrade", "100.00")));
        when(agentExecutionMapper.selectList(any())).thenReturn(List.of(execution("model-a", 1100L)));
        when(aiModelMapper.selectList(any())).thenReturn(List.of(model("model-a", "100.00")));

        var admission = service.evaluateForAgentExecution("agent-1", null, "user-1", "model-a");
        service.recordAdmissionAudit(admission, "agent_execution", "execution-1");

        assertThat(admission.getAllowed()).isTrue();
        assertThat(admission.getStrategy()).isEqualTo("downgrade");
        verify(auditLogMapper).insert(any(com.schemaplexai.model.entity.AuditLog.class));
    }

    @Test
    void shouldListRecentBudgetBlockEventsFromAuditLog() {
        SecurityUtil.setCurrentTenantId("tenant-1");
        AuditLog log = new AuditLog();
        log.setId("audit-1");
        log.setTenantId("tenant-1");
        log.setAction("budget.block");
        log.setResource("agent_execution");
        log.setResourceId("execution-1");
        log.setDetail("{\"severity\":\"HIGH\",\"traceId\":\"trace-1\",\"budgetId\":\"budget-1\",\"strategy\":\"block\",\"usageRate\":108.5,\"remainingAmount\":\"-8.50\",\"message\":\"预算阻断\"}");
        log.setCreatedAt(LocalDateTime.of(2026, 4, 29, 10, 0, 0));
        when(auditLogMapper.selectList(any())).thenReturn(List.of(log));

        var events = service.listRecentBlockEvents(5);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getEventId()).isEqualTo("audit-1");
            assertThat(event.getResourceType()).isEqualTo("agent_execution");
            assertThat(event.getResourceId()).isEqualTo("execution-1");
            assertThat(event.getBudgetId()).isEqualTo("budget-1");
            assertThat(event.getTraceId()).isEqualTo("trace-1");
            assertThat(event.getUsageRate()).isEqualTo(108.5D);
            assertThat(event.getMessage()).isEqualTo("预算阻断");
        });
    }

    private Budget activeBudget(String id, String strategy, String amount) {
        Budget budget = new Budget();
        budget.setId(id);
        budget.setTenantId("tenant-1");
        budget.setBudgetLevel("enterprise");
        budget.setTargetId("tenant-1");
        budget.setBudgetCycle("monthly");
        budget.setBudgetAmount(new BigDecimal(amount));
        budget.setOverLimitStrategy(strategy);
        budget.setStatus("active");
        budget.setAlertThreshold50(Boolean.TRUE);
        budget.setAlertThreshold80(Boolean.TRUE);
        budget.setAlertThreshold100(Boolean.TRUE);
        return budget;
    }

    private AgentExecution execution(String model, long inputTokens) {
        AgentExecution execution = new AgentExecution();
        execution.setTenantId("tenant-1");
        execution.setAiModel(model);
        execution.setTokenInput(inputTokens);
        execution.setTokenOutput(0L);
        execution.setCreatedBy("user-1");
        execution.setCreatedAt(LocalDateTime.now().minusDays(1));
        return execution;
    }

    private AiModel model(String name, String inputPrice) {
        AiModel model = new AiModel();
        model.setName(name);
        model.setInputPrice(new BigDecimal(inputPrice));
        model.setOutputPrice(BigDecimal.ZERO);
        return model;
    }
}
