package com.schemaplexai.service.mq;

import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.TeamAgentOutputMapper;
import com.schemaplexai.model.entity.TeamAgentOutput;
import com.schemaplexai.service.context.ContextCacheService;
import com.schemaplexai.service.mq.message.AgentContextMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentContextConsumerTest {

    private final ContextCacheService cache = mock(ContextCacheService.class);
    private final TeamAgentOutputMapper mapper = mock(TeamAgentOutputMapper.class);
    private final AgentContextConsumer consumer = new AgentContextConsumer(cache, mapper);

    @AfterEach
    void clearTenant() {
        SecurityUtil.clear();
    }

    @Test
    void persistsEachMessageUnderItsOwnTenantOnAReusedThread() {
        when(mapper.insert(any(TeamAgentOutput.class))).thenAnswer(invocation -> {
            TeamAgentOutput output = invocation.getArgument(0);
            assertThat(SecurityUtil.getCurrentTenantId()).isEqualTo(output.getTenantId());
            assertThat(output.getTeamExecutionId()).isEqualTo("team-execution");
            assertThat(output.getOutputContent()).isEqualTo(output.getTenantId() + " 的成员产出");
            return 1;
        });

        assertThat(SecurityUtil.getCurrentTenantId()).isNull();
        consumer.onAgentContextMessage(output("tenant-a"));
        assertThat(SecurityUtil.getCurrentTenantId()).isNull();

        SecurityUtil.setCurrentTenantId("outer-tenant");
        consumer.onAgentContextMessage(output("tenant-b"));
        assertThat(SecurityUtil.getCurrentTenantId()).isEqualTo("outer-tenant");
        verify(mapper, times(2)).insert(any(TeamAgentOutput.class));
    }

    @Test
    void skipsMissingTenantWithoutReusingThreadTenant() {
        SecurityUtil.setCurrentTenantId("outer-tenant");

        consumer.onAgentContextMessage(output(" "));

        verifyNoInteractions(cache, mapper);
        assertThat(SecurityUtil.getCurrentTenantId()).isEqualTo("outer-tenant");
    }

    private AgentContextMessage output(String tenant) {
        return AgentContextMessage.builder()
                .messageType("agent_output")
                .tenantId(tenant)
                .teamAgentId("team-agent")
                .subAgentId("team-member")
                .instanceId("team-execution")
                .executionId("member-execution")
                .outputSummary(tenant + " 的成员产出")
                .build();
    }
}
