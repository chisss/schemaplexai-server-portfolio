package com.schemaplexai.service.security;

import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.security.AgentOutputSecurityGuard.Decision;
import com.schemaplexai.service.security.AgentOutputSecurityGuard.OutputGuardContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentOutputSecurityGuardTest {

    private static final OutputGuardContext CONTEXT = new OutputGuardContext(
            "tenant", "agent", "execution", "agent_execution", "execution",
            null, null, null, null, "原始候选", "展示候选", Map.of());

    @Test
    void shouldFailClosedWhenGuardBeanIsMissing() {
        AgentOutputSecurityGuard guard = new AgentOutputSecurityGuard(provider(null));

        var result = guard.evaluate(CONTEXT);

        assertThat(result.decision()).isEqualTo(Decision.ERROR);
        assertThat(result.allowed()).isFalse();
    }

    @Test
    void shouldFailClosedWhenGuardThrows() {
        SecurityRuntimeGuardService service = mock(SecurityRuntimeGuardService.class);
        when(service.evaluate(any(), isNull())).thenThrow(new IllegalStateException("policy store unavailable"));
        AgentOutputSecurityGuard guard = new AgentOutputSecurityGuard(provider(service));

        var result = guard.evaluate(CONTEXT);

        assertThat(result.decision()).isEqualTo(Decision.ERROR);
        assertThat(result.message()).contains("policy store unavailable");
    }

    @Test
    void shouldTreatUnknownDecisionAsError() {
        SecurityRuntimeGuardService service = mock(SecurityRuntimeGuardService.class);
        SecurityCheckDecisionVO decision = new SecurityCheckDecisionVO();
        decision.setDecision("unexpected");
        when(service.evaluate(any(), isNull())).thenReturn(decision);
        AgentOutputSecurityGuard guard = new AgentOutputSecurityGuard(provider(service));

        var result = guard.evaluate(CONTEXT);

        assertThat(result.decision()).isEqualTo(Decision.ERROR);
        assertThat(result.message()).contains("unexpected");
    }

    @Test
    void shouldPreserveBlockAndPauseDecisions() {
        SecurityRuntimeGuardService service = mock(SecurityRuntimeGuardService.class);
        SecurityCheckDecisionVO block = new SecurityCheckDecisionVO();
        block.setDecision(SecurityComplianceConstant.DECISION_BLOCK);
        block.setMessage("blocked");
        when(service.evaluate(any(), isNull())).thenReturn(block);

        var blocked = new AgentOutputSecurityGuard(provider(service)).evaluate(CONTEXT);

        assertThat(blocked.decision()).isEqualTo(Decision.BLOCK);
        assertThat(blocked.message()).isEqualTo("blocked");
    }

    @Test
    void shouldSendRawContentAndDisplayHashWithoutTreatingSanitizeAsGuardInput() {
        SecurityRuntimeGuardService service = mock(SecurityRuntimeGuardService.class);
        SecurityCheckDecisionVO allow = new SecurityCheckDecisionVO();
        allow.setDecision(SecurityComplianceConstant.DECISION_ALLOW);
        when(service.evaluate(any(), isNull())).thenReturn(allow);

        new AgentOutputSecurityGuard(provider(service)).evaluate(CONTEXT);

        org.mockito.ArgumentCaptor<SecurityRuntimeCheckRequest> captor =
                org.mockito.ArgumentCaptor.forClass(SecurityRuntimeCheckRequest.class);
        org.mockito.Mockito.verify(service).evaluate(captor.capture(), isNull());
        assertThat(captor.getValue().getContent()).isEqualTo("原始候选");
        assertThat(captor.getValue().getContext()).containsKeys("contentSha256", "displayContentSha256");
    }

    private ObjectProvider<SecurityRuntimeGuardService> provider(SecurityRuntimeGuardService service) {
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
