package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionModePromptContributorTest {

    private final ExecutionModePromptContributor contributor = new ExecutionModePromptContributor();

    @Test
    void shouldBuildPlanPromptFromPolicy() {
        ExecutionModePolicy policy = ExecutionModePolicy.builder()
                .mode(ExecutionModeEnum.PLAN)
                .allowReadTools(true)
                .allowWriteTools(true)
                .requireApprovalForWrite(true)
                .build();

        String prompt = contributor.buildPrompt(policy);

        assertThat(prompt).contains("计划模式");
        assertThat(prompt).contains("系统请求用户审批");
        assertThat(prompt).doesNotContain("APPROVAL_REQUIRED");
    }

    @Test
    void shouldBuildSuggestPromptFromPolicy() {
        ExecutionModePolicy policy = ExecutionModePolicy.builder()
                .mode(ExecutionModeEnum.SUGGEST)
                .suggestOnly(true)
                .build();

        String prompt = contributor.buildPrompt(policy);

        assertThat(prompt).contains("建议模式");
        assertThat(prompt).contains("不要期待真实工具被执行");
    }

    @Test
    void shouldBuildAutoPromptFromPolicy() {
        ExecutionModePolicy policy = ExecutionModePolicy.builder()
                .mode(ExecutionModeEnum.AUTO)
                .allowReadTools(true)
                .allowWriteTools(true)
                .build();

        String prompt = contributor.buildPrompt(policy);

        assertThat(prompt).contains("自动模式");
        assertThat(prompt).contains("高风险或写入动作可能由系统要求审批");
    }
}
