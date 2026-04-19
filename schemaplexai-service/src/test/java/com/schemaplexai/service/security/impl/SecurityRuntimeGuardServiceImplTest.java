package com.schemaplexai.service.security.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.dao.mapper.SecurityBindingMapper;
import com.schemaplexai.dao.mapper.SecurityPolicyMapper;
import com.schemaplexai.dao.mapper.SecurityRuleItemMapper;
import com.schemaplexai.dao.mapper.SecurityRulePackMapper;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.security.SecurityIncidentService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityRuntimeGuardServiceImplTest {

    @Test
    void shouldUseRequestTenantIdWhenSecurityContextIsMissing() {
        SecurityPolicyMapper securityPolicyMapper = mock(SecurityPolicyMapper.class);
        SecurityBindingMapper securityBindingMapper = mock(SecurityBindingMapper.class);
        SecurityRulePackMapper securityRulePackMapper = mock(SecurityRulePackMapper.class);
        SecurityRuleItemMapper securityRuleItemMapper = mock(SecurityRuleItemMapper.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);
        SecurityAuditEventService securityAuditEventService = mock(SecurityAuditEventService.class);

        when(securityPolicyMapper.selectList(any())).thenReturn(List.of());
        when(securityRulePackMapper.selectList(any())).thenReturn(List.of());

        SecurityRuntimeGuardServiceImpl service = new SecurityRuntimeGuardServiceImpl(
                securityPolicyMapper,
                securityBindingMapper,
                securityRulePackMapper,
                securityRuleItemMapper,
                securityIncidentService,
                securityAuditEventService,
                new ObjectMapper()
        );

        SecurityRuntimeCheckRequest request = new SecurityRuntimeCheckRequest();
        request.setTenantId("tenant-cron");
        request.setScene(SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_START);
        request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
        request.setResourceType(SecurityComplianceConstant.RESOURCE_TYPE_WORKFLOW_INSTANCE);
        request.setResourceId("wf-1");
        request.setResourceName("定时扫描工作流");
        request.setContent("执行定时扫描");

        var decision = service.evaluate(request, null);

        assertThat(decision.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_ALLOW);
        verify(securityAuditEventService).recordEvent(
                eq("tenant-cron"),
                anyString(),
                eq(SecurityComplianceConstant.EVENT_RUNTIME_CHECK),
                eq(SecurityComplianceConstant.AUDIT_SOURCE_WORKFLOW_EXECUTION),
                eq(SecurityComplianceConstant.AUDIT_STATUS_SUCCESS),
                eq(SecurityComplianceConstant.RISK_LEVEL_MEDIUM),
                eq(SecurityComplianceConstant.DOMAIN_RUNTIME),
                isNull(),
                isNull(),
                eq(SecurityComplianceConstant.RESOURCE_TYPE_WORKFLOW_INSTANCE),
                eq("wf-1"),
                eq("运行时安全检查"),
                anyString(),
                anyMap(),
                isNull()
        );
    }
}
