package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.model.vo.agent.AgentContextBudgetSnapshotVO;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextBudgetServiceTest {

    private final AgentContextBudgetService service = new AgentContextBudgetServiceImpl(new ObjectMapper());

    @Test
    void shouldEstimateBudgetByContextLayers() {
        AgentExecutionContext context = AgentExecutionContext.builder()
                .model("deepseek-v3.2")
                .inputPrompt("abcd")
                .inputContext(Map.of("scene", "营销场景"))
                .teamMemberRoleName("分析师")
                .build();
        SandboxPolicy policy = SandboxPolicy.builder().maxTokenBudget(100L).build();

        AgentContextBudgetSnapshotVO snapshot = service.buildSnapshot(context, policy);

        assertThat(snapshot.getModel()).isEqualTo("deepseek-v3.2");
        assertThat(snapshot.getEstimatedInputTokens()).isGreaterThan(0L);
        assertThat(snapshot.getMaxTokenBudget()).isEqualTo(100L);
        assertThat(snapshot.getLayers()).extracting("name")
                .contains("inputPrompt", "inputContext", "teamContext");
    }

    @Test
    void shouldMarkOverBudget() {
        AgentExecutionContext context = AgentExecutionContext.builder()
                .inputPrompt("x".repeat(1000))
                .build();
        SandboxPolicy policy = SandboxPolicy.builder().maxTokenBudget(10L).build();

        AgentContextBudgetSnapshotVO snapshot = service.buildSnapshot(context, policy);

        assertThat(snapshot.getOverBudget()).isTrue();
    }
}
