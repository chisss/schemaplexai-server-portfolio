package com.schemaplexai.service.agent.runtime.team;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.model.entity.AgentTeamMember;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TeamAgentRuntimeStrategyTest {

    @Test
    void shouldAppendWarningWhenCompletedMemberHasNoOutput() {
        TeamAgentRuntimeStrategy strategy = new TeamAgentRuntimeStrategy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        Map<String, Object> aggregated = strategy.aggregate(List.of(
                Map.of(
                        "roleName", "Lead",
                        "status", AgentExecutionStatusEnum.COMPLETED.getCode(),
                        "outputResult", "主成员已输出最终总结"
                ),
                Map.of(
                        "roleName", "Research",
                        "status", AgentExecutionStatusEnum.COMPLETED.getCode(),
                        "outputResult", ""
                )
        ), 0);

        assertThat(aggregated.get(TeamGraphConstants.STATE_FINAL_STATUS))
                .isEqualTo(AgentExecutionStatusEnum.COMPLETED.getCode());
        assertThat(String.valueOf(aggregated.get(TeamGraphConstants.STATE_FINAL_OUTPUT)))
                .contains("主成员已输出最终总结")
                .contains("## Team 执行告警")
                .contains("[Research] 执行已完成但未产生可用输出");
    }

    @Test
    void shouldFailWhenAllCompletedMembersHaveNoOutput() {
        TeamAgentRuntimeStrategy strategy = new TeamAgentRuntimeStrategy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        Map<String, Object> aggregated = strategy.aggregate(List.of(
                Map.of(
                        "roleName", "Research",
                        "status", AgentExecutionStatusEnum.COMPLETED.getCode(),
                        "outputResult", ""
                )
        ), 0);

        assertThat(aggregated.get(TeamGraphConstants.STATE_FINAL_STATUS))
                .isEqualTo(AgentExecutionStatusEnum.FAILED.getCode());
        assertThat(String.valueOf(aggregated.get(TeamGraphConstants.STATE_FINAL_ERROR)))
                .contains("[Research] 执行已完成但未产生可用输出");
    }

    @Test
    void shouldInjectResumeInputIntoMemberPrompt() {
        TeamAgentRuntimeStrategy strategy = new TeamAgentRuntimeStrategy(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        AgentTeamMember member = new AgentTeamMember();
        member.setRoleName("Lead Planner");
        member.setRoleType("lead_agent");
        member.setDescription("负责汇总并拆解任务");

        String prompt = strategy.buildMemberPrompt(
                "整理发布方案",
                member,
                1,
                "请优先补充人工审批要求",
                Map.of("approvalMode", "manual", "resumeStep", "security_review")
        );

        assertThat(prompt)
                .contains("Lead Planner")
                .contains("这是基于反思后的再次执行")
                .contains("人工补充输入")
                .contains("请优先补充人工审批要求")
                .contains("approvalMode")
                .contains("resumeStep")
                .contains("整理发布方案");
    }
}
