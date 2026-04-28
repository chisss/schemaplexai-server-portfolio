package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import com.schemaplexai.common.enums.ToolExecutionDecisionEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.service.agent.tool.ToolApprovalAmendmentService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionGateTest {

    private final ToolApprovalAmendmentService amendmentService = mock(ToolApprovalAmendmentService.class);
    private final ToolExecutionGate gate = new ToolExecutionGate(amendmentService);

    @Test
    void shouldReturnSuggestOnlyWhenPolicyIsSuggestMode() {
        ToolExecutionGateDecision decision = gate.evaluate(baseRequest(
                ExecutionModePolicy.builder()
                        .mode(ExecutionModeEnum.SUGGEST)
                        .suggestOnly(true)
                        .allowProgressiveTrust(false)
                        .build(),
                ToolIoTypeEnum.WRITE
        ));

        assertThat(decision.getDecision()).isEqualTo(ToolExecutionDecisionEnum.SUGGEST_ONLY);
        assertThat(decision.getReason()).contains("建议模式");
        verify(amendmentService, never()).isAutoApproved("tenant-1", "agent-1", "sys.write", "{\"path\":\"README.md\"}");
    }

    @Test
    void shouldExecuteReadToolWhenPlanAllowsReadTools() {
        ToolExecutionGateDecision decision = gate.evaluate(baseRequest(
                ExecutionModePolicy.builder()
                        .mode(ExecutionModeEnum.PLAN)
                        .allowReadTools(true)
                        .allowWriteTools(true)
                        .requireApprovalForWrite(true)
                        .allowProgressiveTrust(true)
                        .build(),
                ToolIoTypeEnum.READ
        ));

        assertThat(decision.getDecision()).isEqualTo(ToolExecutionDecisionEnum.EXECUTE);
        assertThat(decision.executable()).isTrue();
    }

    @Test
    void shouldPauseWriteToolWhenPlanRequiresApprovalAndNoAmendmentMatches() {
        when(amendmentService.isAutoApproved("tenant-1", "agent-1", "sys.write", "{\"path\":\"README.md\"}"))
                .thenReturn(false);

        ToolExecutionGateDecision decision = gate.evaluate(baseRequest(
                ExecutionModePolicy.builder()
                        .mode(ExecutionModeEnum.PLAN)
                        .allowReadTools(true)
                        .allowWriteTools(true)
                        .requireApprovalForWrite(true)
                        .allowProgressiveTrust(true)
                        .build(),
                ToolIoTypeEnum.WRITE
        ));

        assertThat(decision.getDecision()).isEqualTo(ToolExecutionDecisionEnum.PAUSE_APPROVAL);
        assertThat(decision.getReason()).contains("需要用户审批");
    }

    @Test
    void shouldExecuteWriteToolWhenAutoModeMatchesProgressiveTrust() {
        when(amendmentService.isAutoApproved("tenant-1", "agent-1", "sys.write", "{\"path\":\"README.md\"}"))
                .thenReturn(true);

        ToolExecutionGateDecision decision = gate.evaluate(baseRequest(
                ExecutionModePolicy.builder()
                        .mode(ExecutionModeEnum.AUTO)
                        .allowReadTools(true)
                        .allowWriteTools(true)
                        .requireApprovalForWrite(true)
                        .allowProgressiveTrust(true)
                        .build(),
                ToolIoTypeEnum.WRITE
        ));

        assertThat(decision.getDecision()).isEqualTo(ToolExecutionDecisionEnum.EXECUTE);
        assertThat(decision.executable()).isTrue();
    }

    private ToolExecutionGateRequest baseRequest(ExecutionModePolicy policy, ToolIoTypeEnum ioType) {
        return ToolExecutionGateRequest.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .conversationId("conv-1")
                .round(1)
                .policy(policy)
                .ioType(ioType)
                .systemAgent(true)
                .toolRequest(ToolExecutionRequest.builder()
                        .id("call-1")
                        .name("sys.write")
                        .arguments("{\"path\":\"README.md\"}")
                        .build())
                .build();
    }
}
