package com.schemaplexai.service.security.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecurityBindingMapper;
import com.schemaplexai.dao.mapper.SecurityPolicyMapper;
import com.schemaplexai.dao.mapper.SecurityRuleItemMapper;
import com.schemaplexai.dao.mapper.SecurityRulePackMapper;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.entity.SecurityPolicy;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.security.SecurityMatchedRuleVO;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.security.SecurityIncidentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityRuntimeGuardServiceImplTest {

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void shouldRejectRequestTenantIdWhenSecurityContextIsMissing() {
        SecurityRuntimeGuardServiceImpl service = buildService();
        SecurityRuntimeCheckRequest request = new SecurityRuntimeCheckRequest();
        request.setTenantId("tenant-cron");

        assertThatThrownBy(() -> service.evaluate(request, null))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode());
                    assertThat(exception).hasMessage("租户上下文缺失");
                });
    }

    @Test
    void shouldUseCurrentTenantIdForRuntimeCheck() {
        SecurityPolicyMapper securityPolicyMapper = mock(SecurityPolicyMapper.class);
        SecurityBindingMapper securityBindingMapper = mock(SecurityBindingMapper.class);
        SecurityRulePackMapper securityRulePackMapper = mock(SecurityRulePackMapper.class);
        SecurityRuleItemMapper securityRuleItemMapper = mock(SecurityRuleItemMapper.class);
        SecurityIncidentService securityIncidentService = mock(SecurityIncidentService.class);
        SecurityAuditEventService securityAuditEventService = mock(SecurityAuditEventService.class);

        when(securityPolicyMapper.selectList(any())).thenReturn(List.of());
        when(securityRulePackMapper.selectList(any())).thenReturn(List.of());
        SecurityUtil.setCurrentTenantId("tenant-cron");

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

    @Test
    void shouldCreateIncidentForPolicySimulationWithoutResourceType() {
        SecurityUtil.setCurrentTenantId("tenant-test");
        var policyMapper = mock(SecurityPolicyMapper.class);
        var incidentService = mock(SecurityIncidentService.class);
        var policy = new SecurityPolicy();
        policy.setId("policy-1");
        policy.setPolicyCode("runtime_tool_guard");
        policy.setPolicyScope("tenant");
        policy.setRiskLevel(SecurityComplianceConstant.RISK_LEVEL_HIGH);
        policy.setPolicyConfig(Map.of(
                "forbiddenTools", List.of("test_tool"),
                "reviewRequired", true
        ));
        when(policyMapper.selectList(any())).thenReturn(List.of(policy));
        when(incidentService.createFromDecision(any(), any(), any(), any(), any(), any()))
                .thenReturn("incident-1");
        var service = new SecurityRuntimeGuardServiceImpl(
                policyMapper,
                mock(SecurityBindingMapper.class),
                mock(SecurityRulePackMapper.class),
                mock(SecurityRuleItemMapper.class),
                incidentService,
                mock(SecurityAuditEventService.class),
                new ObjectMapper()
        );
        var request = new SecurityRuntimeCheckRequest();
        request.setScene("tool_pre");
        request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
        request.setToolCode("test_tool");

        var decision = service.evaluate(request, null);

        assertThat(decision.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_PAUSE);
        assertThat(decision.getIncidentId()).isEqualTo("incident-1");
        verify(incidentService).createFromDecision(
                eq(decision), eq("tenant-test"), eq(SecurityComplianceConstant.DOMAIN_RUNTIME),
                eq(SecurityComplianceConstant.RESOURCE_TYPE_POLICY), isNull(), isNull()
        );
    }

    @Test
    void shouldApplyInputLengthLimitAtExecutionButNotForAgentOutput() {
        var service = serviceWithPolicies(lengthPolicy("input_limit", List.of("input", "system_prompt", "instruction_chain")));
        String longContent = "测试正常内容".repeat(250);

        var input = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_AGENT_EXECUTE, longContent);
        assertThat(input.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(input.getMatchedPolicies()).extracting(SecurityMatchedRuleVO::getHitReason)
                .containsExactly("内容长度超出限制: 1200");

        var output = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_OUTPUT, longContent);
        assertThat(output.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_ALLOW);
        assertThat(output.getMatchedPolicies()).isEmpty();

        var toolResult = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_TOOL_POST, longContent);
        assertThat(toolResult.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_ALLOW);
        assertThat(toolResult.getMatchedPolicies()).isEmpty();
    }

    @Test
    void shouldHonorExplicitOutputAndLegacyLengthLimits() {
        var service = serviceWithPolicies(
                lengthPolicy("input_limit", List.of("input")),
                lengthPolicy("output_limit", List.of("input", "output")),
                lengthPolicy("legacy_limit", null)
        );

        var output = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_OUTPUT, "测试正常内容".repeat(250));

        assertThat(output.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(output.getMatchedPolicies()).extracting(SecurityMatchedRuleVO::getSourceCode)
                .containsExactly("output_limit", "legacy_limit");
    }

    @Test
    void shouldKeepContentMatchingActiveWhenInputLengthDoesNotApplyToOutput() {
        var service = serviceWithPolicies(lengthPolicy("input_limit", List.of("input")));

        var output = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_OUTPUT, "测试规则标记");

        assertThat(output.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(output.getMatchedPolicies()).extracting(SecurityMatchedRuleVO::getHitReason)
                .containsExactly("命中关键字: 测试规则标记");

        var toolResult = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_TOOL_POST, "测试规则标记");
        assertThat(toolResult.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(toolResult.getMatchedPolicies()).extracting(SecurityMatchedRuleVO::getHitReason)
                .containsExactly("命中关键字: 测试规则标记");
    }

    @Test
    void shouldEvaluateExplicitToolPrePolicyOnlyAtToolPre() {
        var policy = lengthPolicy("tool-pre-risk", List.of(SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE));
        policy.setPolicyConfig(Map.of("keywords", List.of("测试规则标记"), "reviewRequired", true));
        var service = serviceWithPolicies(policy);

        var input = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_AGENT_EXECUTE, "测试规则标记");
        var tool = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE, "测试规则标记");
        var result = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_TOOL_POST, "测试规则标记");

        assertThat(input.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_ALLOW);
        assertThat(tool.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_PAUSE);
        assertThat(result.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_ALLOW);
    }

    @Test
    void shouldHonorExplicitToolResultAndLegacyLengthLimits() {
        var service = serviceWithPolicies(
                lengthPolicy("input_limit", List.of("input")),
                lengthPolicy("tool_result_limit", List.of("tool_post")),
                lengthPolicy("legacy_limit", null));

        var result = evaluateContent(service, SecurityComplianceConstant.CHECK_SCENE_TOOL_POST, "测试正常内容".repeat(250));

        assertThat(result.getDecision()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(result.getMatchedPolicies()).extracting(SecurityMatchedRuleVO::getSourceCode)
                .containsExactly("tool_result_limit", "legacy_limit");
    }

    private SecurityPolicy lengthPolicy(String code, List<String> controlPoints) {
        var policy = new SecurityPolicy();
        policy.setId(code);
        policy.setPolicyCode(code);
        policy.setEnforcementMode(SecurityComplianceConstant.ENFORCEMENT_BLOCK);
        policy.setControlPoints(controlPoints);
        policy.setPolicyConfig(Map.of("maxLength", 1200, "keywords", List.of("测试规则标记")));
        return policy;
    }

    private SecurityCheckDecisionVO evaluateContent(SecurityRuntimeGuardServiceImpl service, String scene, String content) {
        SecurityUtil.setCurrentTenantId("tenant-test");
        var request = new SecurityRuntimeCheckRequest();
        request.setScene(scene);
        request.setDomainCode(SecurityComplianceConstant.DOMAIN_RUNTIME);
        request.setContent(content);
        return service.evaluate(request, null);
    }

    private SecurityRuntimeGuardServiceImpl serviceWithPolicies(SecurityPolicy... policies) {
        var policyMapper = mock(SecurityPolicyMapper.class);
        when(policyMapper.selectList(any())).thenReturn(List.of(policies));
        return new SecurityRuntimeGuardServiceImpl(
                policyMapper,
                mock(SecurityBindingMapper.class),
                mock(SecurityRulePackMapper.class),
                mock(SecurityRuleItemMapper.class),
                mock(SecurityIncidentService.class),
                mock(SecurityAuditEventService.class),
                new ObjectMapper()
        );
    }

    private SecurityRuntimeGuardServiceImpl buildService() {
        return new SecurityRuntimeGuardServiceImpl(
                mock(SecurityPolicyMapper.class),
                mock(SecurityBindingMapper.class),
                mock(SecurityRulePackMapper.class),
                mock(SecurityRuleItemMapper.class),
                mock(SecurityIncidentService.class),
                mock(SecurityAuditEventService.class),
                new ObjectMapper()
        );
    }
}
