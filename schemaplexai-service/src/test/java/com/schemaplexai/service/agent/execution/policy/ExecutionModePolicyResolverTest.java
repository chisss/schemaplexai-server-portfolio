package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionModePolicyResolverTest {

    private final ExecutionModePolicyResolver resolver = new ExecutionModePolicyResolver();

    @Test
    void shouldResolvePlanModePolicy() {
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionMode("plan")
                .systemAgent(true)
                .build();
        Agent agent = new Agent();
        agent.setIsSystemAgent(true);

        ExecutionModePolicy policy = resolver.resolve(context, agent);

        assertThat(policy.getMode()).isEqualTo(ExecutionModeEnum.PLAN);
        assertThat(policy.isAllowReadTools()).isTrue();
        assertThat(policy.isAllowWriteTools()).isTrue();
        assertThat(policy.isRequireApprovalForWrite()).isTrue();
        assertThat(policy.isAllowProgressiveTrust()).isTrue();
        assertThat(policy.isSuggestOnly()).isFalse();
        assertThat(policy.isApplyToNonSystemAgent()).isTrue();
    }

    @Test
    void shouldResolveSuggestModeAsPreviewOnlyPolicy() {
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionMode("suggest")
                .systemAgent(true)
                .build();
        Agent agent = new Agent();
        agent.setIsSystemAgent(true);

        ExecutionModePolicy policy = resolver.resolve(context, agent);

        assertThat(policy.getMode()).isEqualTo(ExecutionModeEnum.SUGGEST);
        assertThat(policy.isAllowReadTools()).isFalse();
        assertThat(policy.isAllowWriteTools()).isFalse();
        assertThat(policy.isRequireApprovalForWrite()).isTrue();
        assertThat(policy.isAllowProgressiveTrust()).isFalse();
        assertThat(policy.isSuggestOnly()).isTrue();
        assertThat(policy.isApplyToNonSystemAgent()).isTrue();
    }

    @Test
    void shouldFallbackUnknownModeToAutoPolicy() {
        AgentExecutionContext context = AgentExecutionContext.builder()
                .executionMode("unknown")
                .build();

        ExecutionModePolicy policy = resolver.resolve(context, new Agent());

        assertThat(policy.getMode()).isEqualTo(ExecutionModeEnum.AUTO);
        assertThat(policy.isAllowReadTools()).isTrue();
        assertThat(policy.isAllowWriteTools()).isTrue();
        assertThat(policy.isRequireApprovalForWrite()).isTrue();
        assertThat(policy.isAllowProgressiveTrust()).isTrue();
        assertThat(policy.isSuggestOnly()).isFalse();
    }
}
