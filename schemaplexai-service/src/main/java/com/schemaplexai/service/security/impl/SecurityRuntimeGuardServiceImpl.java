package com.schemaplexai.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecurityBindingMapper;
import com.schemaplexai.dao.mapper.SecurityPolicyMapper;
import com.schemaplexai.dao.mapper.SecurityRuleItemMapper;
import com.schemaplexai.dao.mapper.SecurityRulePackMapper;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.entity.SecurityBinding;
import com.schemaplexai.model.entity.SecurityPolicy;
import com.schemaplexai.model.entity.SecurityRuleItem;
import com.schemaplexai.model.entity.SecurityRulePack;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.security.SecurityMatchedRuleVO;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.security.SecurityIncidentService;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 安全运行时检查服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityRuntimeGuardServiceImpl implements SecurityRuntimeGuardService {

    private final SecurityPolicyMapper securityPolicyMapper;
    private final SecurityBindingMapper securityBindingMapper;
    private final SecurityRulePackMapper securityRulePackMapper;
    private final SecurityRuleItemMapper securityRuleItemMapper;
    private final SecurityIncidentService securityIncidentService;
    private final SecurityAuditEventService securityAuditEventService;
    private final ObjectMapper objectMapper;

    @Override
    public SecurityCheckDecisionVO evaluate(SecurityRuntimeCheckRequest request, SecurityAuditContext auditContext) {
        String traceId = StringUtils.hasText(request.getTraceId())
                ? request.getTraceId()
                : UUID.randomUUID().toString().replace("-", "");
        String tenantId = resolveTenantId(request);
        String content = buildCorpus(request);

        List<SecurityMatchedRuleVO> matchedPolicies = matchPolicies(request, tenantId, content);
        List<SecurityMatchedRuleVO> matchedRulePacks = matchRulePacks(request, tenantId, content);

        String decision = resolveDecision(matchedPolicies, matchedRulePacks);
        var result = new SecurityCheckDecisionVO();
        result.setDecision(decision);
        result.setTraceId(traceId);
        result.setMatchedPolicies(matchedPolicies);
        result.setMatchedRulePacks(matchedRulePacks);
        result.setShouldCreateIncident(!SecurityComplianceConstant.DECISION_ALLOW.equals(decision));
        result.setMessage(buildMessage(decision, matchedPolicies, matchedRulePacks));
        result.setUserActionTip(buildUserTip(decision));
        result.setAdminActionTip(buildAdminTip(decision));

        if (Boolean.TRUE.equals(result.getShouldCreateIncident())) {
            String incidentId = securityIncidentService.createFromDecision(
                    result,
                    tenantId,
                    request.getDomainCode(),
                    request.getResourceType(),
                    request.getResourceId(),
                    request.getResourceName()
            );
            result.setIncidentId(incidentId);
        }

        recordAuditEvent(request, auditContext, result, tenantId, traceId);
        return result;
    }

    private String resolveTenantId(SecurityRuntimeCheckRequest request) {
        if (request != null && StringUtils.hasText(request.getTenantId())) {
            return request.getTenantId().trim();
        }
        return SecurityUtil.getCurrentTenantId();
    }

    private List<SecurityMatchedRuleVO> matchPolicies(SecurityRuntimeCheckRequest request, String tenantId, String corpus) {
        var wrapper = new LambdaQueryWrapper<SecurityPolicy>()
                .eq(StringUtils.hasText(tenantId), SecurityPolicy::getTenantId, tenantId)
                .eq(SecurityPolicy::getStatus, SecurityComplianceConstant.STATUS_ACTIVE)
                .eq(StringUtils.hasText(request.getDomainCode()), SecurityPolicy::getDomainCode, request.getDomainCode())
                .orderByDesc(SecurityPolicy::getRiskLevel)
                .orderByDesc(SecurityPolicy::getUpdatedAt);
        List<SecurityPolicy> policies = securityPolicyMapper.selectList(wrapper);
        List<SecurityMatchedRuleVO> results = new ArrayList<>();
        for (SecurityPolicy policy : policies) {
            if (!matchesTarget(policy, request, tenantId)) {
                continue;
            }
            String hitReason = evaluatePolicy(policy, request, corpus);
            if (!StringUtils.hasText(hitReason)) {
                continue;
            }
            var matched = new SecurityMatchedRuleVO();
            matched.setSourceType(SecurityComplianceConstant.SOURCE_TYPE_POLICY);
            matched.setSourceId(policy.getId());
            matched.setSourceCode(policy.getPolicyCode());
            matched.setSourceName(policy.getPolicyName());
            matched.setRiskLevel(policy.getRiskLevel());
            matched.setAction(resolveAction(policy.getEnforcementMode(), policy.getPolicyConfig()));
            matched.setHitReason(hitReason);
            results.add(matched);
        }
        return results;
    }

    private List<SecurityMatchedRuleVO> matchRulePacks(SecurityRuntimeCheckRequest request, String tenantId, String corpus) {
        List<SecurityRulePack> packs = securityRulePackMapper.selectList(
                new LambdaQueryWrapper<SecurityRulePack>()
                        .eq(StringUtils.hasText(tenantId), SecurityRulePack::getTenantId, tenantId)
                        .eq(SecurityRulePack::getStatus, SecurityComplianceConstant.STATUS_ACTIVE)
                        .orderByDesc(SecurityRulePack::getUpdatedAt)
        );
        List<SecurityMatchedRuleVO> results = new ArrayList<>();
        for (SecurityRulePack pack : packs) {
            if (!matchesRulePackBinding(pack.getId(), request, tenantId)) {
                continue;
            }
            List<SecurityRuleItem> items = securityRuleItemMapper.selectList(
                    new LambdaQueryWrapper<SecurityRuleItem>()
                            .eq(SecurityRuleItem::getPackId, pack.getId())
                            .eq(SecurityRuleItem::getEnabled, true)
                            .orderByAsc(SecurityRuleItem::getSortOrder)
            );
            for (SecurityRuleItem item : items) {
                String hitReason = evaluateRuleItem(item, corpus);
                if (!StringUtils.hasText(hitReason)) {
                    continue;
                }
                var matched = new SecurityMatchedRuleVO();
                matched.setSourceType(SecurityComplianceConstant.SOURCE_TYPE_RULE_PACK);
                matched.setSourceId(pack.getId());
                matched.setSourceCode(pack.getPackCode());
                matched.setSourceName(pack.getPackName());
                matched.setItemId(item.getId());
                matched.setItemCode(item.getItemCode());
                matched.setItemName(item.getItemName());
                matched.setRiskLevel(item.getRiskLevel());
                matched.setAction(StringUtils.hasText(item.getAction()) ? item.getAction() : pack.getDefaultAction());
                matched.setHitReason(hitReason);
                results.add(matched);
            }
        }
        return results;
    }

    private boolean matchesTarget(SecurityPolicy policy, SecurityRuntimeCheckRequest request, String tenantId) {
        List<SecurityBinding> bindings = securityBindingMapper.selectList(
                new LambdaQueryWrapper<SecurityBinding>()
                        .eq(StringUtils.hasText(tenantId), SecurityBinding::getTenantId, tenantId)
                        .eq(SecurityBinding::getSourceType, SecurityComplianceConstant.SOURCE_TYPE_POLICY)
                        .eq(SecurityBinding::getSourceId, policy.getId())
                        .eq(SecurityBinding::getStatus, SecurityComplianceConstant.STATUS_ACTIVE)
        );
        if (!bindings.isEmpty()) {
            return bindings.stream().anyMatch(binding -> matchesBinding(binding, request));
        }
        Map<String, Object> selector = policy.getTargetSelector();
        if (selector == null || selector.isEmpty()) {
            return true;
        }
        String scope = stringValue(selector.get("scope"));
        if (!StringUtils.hasText(scope)) {
            scope = policy.getPolicyScope();
        }
        if (SecurityComplianceConstant.SCOPE_TENANT.equals(scope)) {
            return true;
        }
        if (SecurityComplianceConstant.SCOPE_AGENT.equals(scope)) {
            return containsValue(selector.get("agentIds"), request.getAgentId());
        }
        if (SecurityComplianceConstant.SCOPE_WORKFLOW.equals(scope)) {
            return containsValue(selector.get("workflowTemplateIds"), request.getWorkflowInstanceId())
                    || containsValue(selector.get("workflowTemplateIds"), request.getWorkflowNodeId());
        }
        if (SecurityComplianceConstant.SCOPE_PROJECT.equals(scope)) {
            return containsValue(selector.get("workspaceIds"), request.getWorkspaceId());
        }
        return true;
    }

    private boolean matchesRulePackBinding(String packId, SecurityRuntimeCheckRequest request, String tenantId) {
        List<SecurityBinding> bindings = securityBindingMapper.selectList(
                new LambdaQueryWrapper<SecurityBinding>()
                        .eq(StringUtils.hasText(tenantId), SecurityBinding::getTenantId, tenantId)
                        .eq(SecurityBinding::getSourceType, SecurityComplianceConstant.SOURCE_TYPE_RULE_PACK)
                        .eq(SecurityBinding::getSourceId, packId)
                        .eq(SecurityBinding::getStatus, SecurityComplianceConstant.STATUS_ACTIVE)
        );
        if (bindings.isEmpty()) {
            return SecurityComplianceConstant.DOMAIN_INDUSTRY.equals(request.getDomainCode())
                    || (request.getTags() != null && !request.getTags().isEmpty());
        }
        return bindings.stream().anyMatch(binding -> matchesBinding(binding, request));
    }

    private boolean matchesBinding(SecurityBinding binding, SecurityRuntimeCheckRequest request) {
        if (!isEffective(binding)) {
            return false;
        }
        if (SecurityComplianceConstant.SCOPE_TENANT.equals(binding.getBindingType())) {
            return true;
        }
        if (SecurityComplianceConstant.SCOPE_AGENT.equals(binding.getBindingType())) {
            return StringUtils.hasText(request.getAgentId()) && request.getAgentId().equals(binding.getTargetId());
        }
        if (SecurityComplianceConstant.SCOPE_WORKFLOW.equals(binding.getBindingType())) {
            return StringUtils.hasText(request.getWorkflowInstanceId()) && request.getWorkflowInstanceId().equals(binding.getTargetId());
        }
        if (SecurityComplianceConstant.SCOPE_PROJECT.equals(binding.getBindingType())) {
            return StringUtils.hasText(request.getWorkspaceId()) && request.getWorkspaceId().equals(binding.getTargetId());
        }
        return false;
    }

    private boolean isEffective(SecurityBinding binding) {
        LocalDateTime now = LocalDateTime.now();
        if (binding.getEffectiveFrom() != null && now.isBefore(binding.getEffectiveFrom())) {
            return false;
        }
        return binding.getEffectiveTo() == null || !now.isAfter(binding.getEffectiveTo());
    }

    private String evaluatePolicy(SecurityPolicy policy, SecurityRuntimeCheckRequest request, String corpus) {
        Map<String, Object> config = policy.getPolicyConfig() == null ? Map.of() : policy.getPolicyConfig();
        List<String> keywords = stringList(config.get("keywords"));
        if (!keywords.isEmpty()) {
            for (String keyword : keywords) {
                if (corpus.contains(keyword.toLowerCase())) {
                    return "命中关键字: " + keyword;
                }
            }
        }
        List<String> forbiddenTools = stringList(config.get("forbiddenTools"));
        if (!forbiddenTools.isEmpty() && StringUtils.hasText(request.getToolCode())) {
            for (String tool : forbiddenTools) {
                if (tool.equalsIgnoreCase(request.getToolCode())) {
                    return "命中高危工具: " + tool;
                }
            }
        }
        List<String> forbiddenCommands = stringList(config.get("forbiddenCommands"));
        if (!forbiddenCommands.isEmpty()) {
            for (String command : forbiddenCommands) {
                if (corpus.contains(command.toLowerCase())) {
                    return "命中高危命令: " + command;
                }
            }
        }
        List<String> regexPatterns = stringList(config.get("regexPatterns"));
        if (!regexPatterns.isEmpty()) {
            for (String regex : regexPatterns) {
                try {
                    if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(corpus).find()) {
                        return "命中正则模式: " + regex;
                    }
                } catch (Exception exception) {
                    log.warn("安全策略正则非法: policyCode={}, regex={}, error={}",
                            policy.getPolicyCode(), regex, exception.getMessage());
                }
            }
        }
        Number maxLength = numberValue(config.get("maxLength"));
        if (maxLength != null && StringUtils.hasText(request.getContent())
                && request.getContent().length() > maxLength.intValue()) {
            return "内容长度超出限制: " + maxLength;
        }

        if (Boolean.TRUE.equals(config.get("forbidMemoryWrite"))
                && hasAnyTag(request.getTags(), List.of("memory_write", "knowledge_write"))) {
            return "命中知识回写限制";
        }

        if (Boolean.TRUE.equals(config.get("requireMaskedOutput")) && containsSensitiveData(request.getContent())) {
            return "检测到未脱敏敏感信息";
        }

        List<String> allowedRegions = stringList(config.get("allowedRegions"));
        if (!allowedRegions.isEmpty()) {
            String currentRegion = contextString(request.getContext(), "region", "workspaceRegion", "storageRegion");
            if (StringUtils.hasText(currentRegion) && !allowedRegions.contains(currentRegion.trim().toLowerCase())) {
                return "命中区域限制: " + currentRegion;
            }
        }

        String transportProtocol = stringValue(config.get("transportProtocol"));
        if (Boolean.TRUE.equals(config.get("encryptTransport")) && StringUtils.hasText(transportProtocol)) {
            String currentTransport = contextString(request.getContext(), "transportProtocol");
            if (StringUtils.hasText(currentTransport)
                    && !transportProtocol.equalsIgnoreCase(currentTransport.trim())) {
                return "传输协议不符合要求: " + currentTransport;
            }
        }

        String storageAlgorithm = stringValue(config.get("storageAlgorithm"));
        if (Boolean.TRUE.equals(config.get("encryptStorage")) && StringUtils.hasText(storageAlgorithm)) {
            String currentStorageAlgorithm = contextString(request.getContext(), "storageAlgorithm");
            if (StringUtils.hasText(currentStorageAlgorithm)
                    && !storageAlgorithm.equalsIgnoreCase(currentStorageAlgorithm.trim())) {
                return "存储加密算法不符合要求: " + currentStorageAlgorithm;
            }
        }

        Number sessionTtlMinutes = numberValue(config.get("sessionTtlMinutes"));
        Number currentSessionTtl = contextNumber(request.getContext(), "sessionTtlMinutes", "currentSessionTtlMinutes");
        if (sessionTtlMinutes != null && currentSessionTtl != null
                && currentSessionTtl.intValue() > sessionTtlMinutes.intValue()) {
            return "会话时长超出限制: " + currentSessionTtl;
        }

        Number maxSessions = numberValue(config.get("maxSessions"));
        Number currentSessionCount = contextNumber(request.getContext(), "sessionCount", "activeSessions");
        if (maxSessions != null && currentSessionCount != null
                && currentSessionCount.intValue() > maxSessions.intValue()) {
            return "活跃会话数超出限制: " + currentSessionCount;
        }

        Number tenantQps = numberValue(config.get("tenantQps"));
        Number currentQps = contextNumber(request.getContext(), "tenantQps", "currentQps", "qps");
        if (tenantQps != null && currentQps != null && currentQps.intValue() > tenantQps.intValue()) {
            return "租户请求速率超限: " + currentQps;
        }

        Number userDailyQuota = numberValue(config.get("userDailyQuota"));
        Number currentDailyUsage = contextNumber(request.getContext(), "userDailyUsage", "dailyUsage");
        if (userDailyQuota != null && currentDailyUsage != null
                && currentDailyUsage.intValue() > userDailyQuota.intValue()) {
            return "用户日配额超限: " + currentDailyUsage;
        }

        List<String> highRiskTags = stringList(config.get("highRiskTags"));
        if (!highRiskTags.isEmpty() && hasAnyTag(request.getTags(), highRiskTags)) {
            return "命中高风险标签";
        }
        return null;
    }

    private String evaluateRuleItem(SecurityRuleItem item, String corpus) {
        String matchType = item.getMatchType();
        String matchContent = item.getMatchContent();
        if (!StringUtils.hasText(matchContent)) {
            return null;
        }
        if ("exact".equalsIgnoreCase(matchType) && corpus.contains(matchContent.toLowerCase())) {
            return "命中规则项精确内容";
        }
        if ("regex".equalsIgnoreCase(matchType)) {
            try {
                if (Pattern.compile(matchContent, Pattern.CASE_INSENSITIVE).matcher(corpus).find()) {
                    return "命中规则项正则";
                }
            } catch (Exception exception) {
                log.warn("规则项正则非法: itemCode={}, regex={}, error={}",
                        item.getItemCode(), matchContent, exception.getMessage());
            }
            return null;
        }
        if (corpus.contains(matchContent.toLowerCase())) {
            return "命中规则项关键字";
        }
        return null;
    }

    private String resolveDecision(List<SecurityMatchedRuleVO> matchedPolicies, List<SecurityMatchedRuleVO> matchedRulePacks) {
        List<String> actions = new ArrayList<>();
        matchedPolicies.forEach(item -> actions.add(item.getAction()));
        matchedRulePacks.forEach(item -> actions.add(item.getAction()));
        if (actions.stream().anyMatch(SecurityComplianceConstant.DECISION_BLOCK::equals)) {
            return SecurityComplianceConstant.DECISION_BLOCK;
        }
        if (actions.stream().anyMatch(SecurityComplianceConstant.DECISION_PAUSE::equals)) {
            return SecurityComplianceConstant.DECISION_PAUSE;
        }
        if (actions.stream().anyMatch(SecurityComplianceConstant.DECISION_WARN::equals)) {
            return SecurityComplianceConstant.DECISION_WARN;
        }
        return SecurityComplianceConstant.DECISION_ALLOW;
    }

    private String resolveAction(String enforcementMode, Map<String, Object> config) {
        String hitAction = stringValue(config.get("hitAction"));
        if (StringUtils.hasText(hitAction) && SecurityComplianceConstant.CHECK_DECISIONS.contains(hitAction)) {
            return hitAction;
        }
        if (Boolean.TRUE.equals(config.get("reviewRequired"))) {
            return SecurityComplianceConstant.DECISION_PAUSE;
        }
        if (SecurityComplianceConstant.ENFORCEMENT_BLOCK.equals(enforcementMode)) {
            return SecurityComplianceConstant.DECISION_BLOCK;
        }
        if (SecurityComplianceConstant.ENFORCEMENT_WARN.equals(enforcementMode)) {
            return SecurityComplianceConstant.DECISION_WARN;
        }
        return SecurityComplianceConstant.DECISION_ALLOW;
    }

    private void recordAuditEvent(SecurityRuntimeCheckRequest request,
                                  SecurityAuditContext auditContext,
                                  SecurityCheckDecisionVO result,
                                  String tenantId,
                                  String traceId) {
        String eventStatus = switch (result.getDecision()) {
            case SecurityComplianceConstant.DECISION_BLOCK -> SecurityComplianceConstant.AUDIT_STATUS_BLOCKED;
            case SecurityComplianceConstant.DECISION_PAUSE, SecurityComplianceConstant.DECISION_WARN ->
                    SecurityComplianceConstant.AUDIT_STATUS_WARNING;
            default -> SecurityComplianceConstant.AUDIT_STATUS_SUCCESS;
        };
        securityAuditEventService.recordEvent(
                tenantId,
                traceId,
                SecurityComplianceConstant.EVENT_RUNTIME_CHECK,
                resolveEventSource(request.getScene()),
                eventStatus,
                resolveRiskLevel(result),
                request.getDomainCode(),
                firstPolicyId(result),
                firstPolicyCode(result),
                request.getResourceType(),
                request.getResourceId(),
                "运行时安全检查",
                result.getMessage(),
                Map.of(
                        "scene", safeValue(request.getScene()),
                        "decision", result.getDecision(),
                        "incidentId", safeValue(result.getIncidentId()),
                        "matchedPolicies", result.getMatchedPolicies() == null ? List.of() : result.getMatchedPolicies(),
                        "matchedRulePacks", result.getMatchedRulePacks() == null ? List.of() : result.getMatchedRulePacks(),
                        "resourceName", safeValue(request.getResourceName())
                ),
                auditContext
        );
    }

    private String resolveEventSource(String scene) {
        if (SecurityComplianceConstant.CHECK_SCENE_AGENT_EXECUTE.equals(scene)) {
            return SecurityComplianceConstant.AUDIT_SOURCE_AGENT_EXECUTION;
        }
        if (SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_START.equals(scene)
                || SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_NODE.equals(scene)) {
            return SecurityComplianceConstant.AUDIT_SOURCE_WORKFLOW_EXECUTION;
        }
        return SecurityComplianceConstant.AUDIT_SOURCE_RUNTIME_ENGINE;
    }

    private String resolveRiskLevel(SecurityCheckDecisionVO result) {
        if (result.getMatchedPolicies() != null && !result.getMatchedPolicies().isEmpty()) {
            return result.getMatchedPolicies().getFirst().getRiskLevel();
        }
        if (result.getMatchedRulePacks() != null && !result.getMatchedRulePacks().isEmpty()) {
            return result.getMatchedRulePacks().getFirst().getRiskLevel();
        }
        return SecurityComplianceConstant.RISK_LEVEL_MEDIUM;
    }

    private String firstPolicyId(SecurityCheckDecisionVO result) {
        return result.getMatchedPolicies() != null && !result.getMatchedPolicies().isEmpty()
                ? result.getMatchedPolicies().getFirst().getSourceId()
                : null;
    }

    private String firstPolicyCode(SecurityCheckDecisionVO result) {
        return result.getMatchedPolicies() != null && !result.getMatchedPolicies().isEmpty()
                ? result.getMatchedPolicies().getFirst().getSourceCode()
                : null;
    }

    private String buildMessage(String decision,
                                List<SecurityMatchedRuleVO> matchedPolicies,
                                List<SecurityMatchedRuleVO> matchedRulePacks) {
        int total = matchedPolicies.size() + matchedRulePacks.size();
        if (SecurityComplianceConstant.DECISION_BLOCK.equals(decision)) {
            return "请求命中 " + total + " 条安全规则，已被阻断";
        }
        if (SecurityComplianceConstant.DECISION_PAUSE.equals(decision)) {
            return "请求命中高风险安全规则，流程已暂停等待人工处理";
        }
        if (SecurityComplianceConstant.DECISION_WARN.equals(decision)) {
            return "请求命中 " + total + " 条安全规则，请谨慎继续";
        }
        return "安全检查通过";
    }

    private String buildUserTip(String decision) {
        return switch (decision) {
            case SecurityComplianceConstant.DECISION_BLOCK -> "当前操作涉及高风险内容，系统已阻止继续执行。";
            case SecurityComplianceConstant.DECISION_PAUSE -> "当前操作需要管理员复核，业务流程已暂停。";
            case SecurityComplianceConstant.DECISION_WARN -> "当前操作存在安全风险，请确认后继续。";
            default -> "未发现影响执行的安全风险。";
        };
    }

    private String buildAdminTip(String decision) {
        return switch (decision) {
            case SecurityComplianceConstant.DECISION_BLOCK -> "请检查命中策略配置、业务意图及是否需要豁免。";
            case SecurityComplianceConstant.DECISION_PAUSE -> "请在风控工作台复核事件并决定恢复或终止流程。";
            case SecurityComplianceConstant.DECISION_WARN -> "建议关注后续执行结果并检查是否需要升级为阻断。";
            default -> "可仅保留审计记录，无需额外操作。";
        };
    }

    private String buildCorpus(SecurityRuntimeCheckRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", request.getContent());
        payload.put("toolCode", request.getToolCode());
        payload.put("arguments", request.getArguments());
        payload.put("context", request.getContext());
        payload.put("tags", request.getTags());
        try {
            return objectMapper.writeValueAsString(payload).toLowerCase();
        } catch (Exception exception) {
            return String.valueOf(payload).toLowerCase();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                result.add(String.valueOf(item).trim().toLowerCase());
            }
        }
        return result;
    }

    private boolean containsValue(Object value, String target) {
        if (!(value instanceof List<?> list) || !StringUtils.hasText(target)) {
            return false;
        }
        for (Object item : list) {
            if (target.equals(String.valueOf(item))) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Number numberValue(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Number contextNumber(Map<String, Object> context, String... keys) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Number value = numberValue(context.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String contextString(Map<String, Object> context, String... keys) {
        if (context == null || context.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = context.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private boolean hasAnyTag(List<String> requestTags, List<String> expectedTags) {
        if (requestTags == null || requestTags.isEmpty() || expectedTags == null || expectedTags.isEmpty()) {
            return false;
        }
        List<String> normalizedTags = requestTags.stream()
                .filter(StringUtils::hasText)
                .map(item -> item.trim().toLowerCase())
                .toList();
        for (String expectedTag : expectedTags) {
            if (normalizedTags.contains(expectedTag)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSensitiveData(String content) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        return Pattern.compile("(1\\d{10})|([a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})|(\\d{15}(\\d{2}[0-9xX])?)")
                .matcher(content)
                .find();
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }
}
