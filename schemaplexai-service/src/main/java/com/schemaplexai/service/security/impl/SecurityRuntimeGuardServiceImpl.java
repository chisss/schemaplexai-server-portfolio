package com.schemaplexai.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 安全运行时检查服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityRuntimeGuardServiceImpl implements SecurityRuntimeGuardService {

    private static final Set<String> INPUT_LENGTH_CONTROL_POINTS = Set.of("input", "system_prompt", "instruction_chain");
    private static final Set<String> USAGE_BOUNDARY_SCENES = Set.of(
            SecurityComplianceConstant.CHECK_SCENE_AGENT_EXECUTE,
            SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_START
    );
    private static final Set<String> RUNTIME_SCENES = Set.of(
            SecurityComplianceConstant.CHECK_SCENE_AGENT_EXECUTE,
            SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_START,
            SecurityComplianceConstant.CHECK_SCENE_WORKFLOW_NODE,
            SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE,
            SecurityComplianceConstant.CHECK_SCENE_TOOL_POST,
            SecurityComplianceConstant.CHECK_SCENE_OUTPUT
    );

    private final SecurityPolicyMapper securityPolicyMapper;
    private final SecurityBindingMapper securityBindingMapper;
    private final SecurityRulePackMapper securityRulePackMapper;
    private final SecurityRuleItemMapper securityRuleItemMapper;
    private final SecurityIncidentService securityIncidentService;
    private final SecurityAuditEventService securityAuditEventService;
    private final ObjectMapper objectMapper;
    private final Map<String, WindowCounter> qpsWindows = new ConcurrentHashMap<>();
    private final Map<String, DailyCounter> dailyUsage = new ConcurrentHashMap<>();

    @Value("${schemaplexai.security.runtime-facts.region:cn-hz}")
    private String trustedRegion = "cn-hz";
    @Value("${schemaplexai.security.runtime-facts.transport-protocol:TLS1.3}")
    private String trustedTransportProtocol = "TLS1.3";
    @Value("${schemaplexai.security.runtime-facts.storage-algorithm:AES-256}")
    private String trustedStorageAlgorithm = "AES-256";
    @Value("${schemaplexai.security.runtime-facts.session-ttl-minutes:0}")
    private int trustedSessionTtlMinutes = 0;
    @Value("${schemaplexai.security.runtime-facts.active-sessions:1}")
    private int trustedActiveSessions = 1;

    @Override
    public SecurityCheckDecisionVO evaluate(SecurityRuntimeCheckRequest request, SecurityAuditContext auditContext) {
        String traceId = StringUtils.hasText(request.getTraceId())
                ? request.getTraceId()
                : UUID.randomUUID().toString().replace("-", "");
        String tenantId = resolveTenantId(request);
        String content = buildCorpus(request);
        String resourceType = StringUtils.hasText(request.getResourceType())
                ? request.getResourceType()
                : SecurityComplianceConstant.RESOURCE_TYPE_POLICY;
        Map<String, Object> trustedFacts = resolveTrustedFacts(request, tenantId);

        List<SecurityMatchedRuleVO> matchedPolicies = matchPolicies(request, tenantId, content, trustedFacts);
        List<SecurityMatchedRuleVO> matchedRulePacks = matchRulePacks(request, tenantId, content, trustedFacts);

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
                    resourceType,
                    request.getResourceId(),
                    request.getResourceName()
            );
            result.setIncidentId(incidentId);
        }

        recordAuditEvent(request, auditContext, result, tenantId, traceId, resourceType, trustedFacts);
        return result;
    }

    private String resolveTenantId(SecurityRuntimeCheckRequest request) {
        String currentTenantId = requireTenantId();
        if (request != null && StringUtils.hasText(request.getTenantId())
                && !currentTenantId.equals(request.getTenantId().trim())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "请求租户与当前租户不一致");
        }
        return currentTenantId;
    }

    private List<SecurityMatchedRuleVO> matchPolicies(SecurityRuntimeCheckRequest request, String tenantId,
                                                      String corpus, Map<String, Object> trustedFacts) {
        var wrapper = new LambdaQueryWrapper<SecurityPolicy>()
                .eq(SecurityPolicy::getTenantId, tenantId)
                .eq(SecurityPolicy::getStatus, SecurityComplianceConstant.STATUS_ACTIVE)
                .eq(StringUtils.hasText(request.getDomainCode()), SecurityPolicy::getDomainCode, request.getDomainCode())
                .orderByDesc(SecurityPolicy::getRiskLevel)
                .orderByDesc(SecurityPolicy::getUpdatedAt);
        List<SecurityPolicy> policies = securityPolicyMapper.selectList(wrapper);
        List<SecurityMatchedRuleVO> results = new ArrayList<>();
        for (SecurityPolicy policy : policies) {
            if (!matchesScene(policy, request.getScene()) || !matchesTarget(policy, request, tenantId)) {
                continue;
            }
            String hitReason = evaluatePolicy(policy, request, corpus, trustedFacts);
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

    private List<SecurityMatchedRuleVO> matchRulePacks(SecurityRuntimeCheckRequest request, String tenantId,
                                                       String corpus, Map<String, Object> trustedFacts) {
        List<SecurityRulePack> packs = securityRulePackMapper.selectList(
                new LambdaQueryWrapper<SecurityRulePack>()
                        .eq(SecurityRulePack::getTenantId, tenantId)
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
                        .eq(SecurityBinding::getTenantId, tenantId)
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
                        .eq(SecurityBinding::getTenantId, tenantId)
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

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId;
    }

    private String evaluatePolicy(SecurityPolicy policy, SecurityRuntimeCheckRequest request, String corpus,
                                  Map<String, Object> trustedFacts) {
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
                && request.getContent().length() > maxLength.intValue()
                && appliesLengthLimit(policy, request)) {
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
            String currentRegion = factString(trustedFacts, "region");
            if (isNotApplicable(currentRegion)) {
                log.debug("区域限制不适用于当前检查: tenantId={}", request.getTenantId());
            } else if (!StringUtils.hasText(currentRegion) || isUnknownFact(currentRegion)) {
                return "区域合规事实不可用: " + safeFact(currentRegion);
            } else if (!allowedRegions.contains(currentRegion.trim().toLowerCase())) {
                return "命中区域限制: " + currentRegion;
            }
        }

        String transportProtocol = stringValue(config.get("transportProtocol"));
        if (Boolean.TRUE.equals(config.get("encryptTransport")) && StringUtils.hasText(transportProtocol)) {
            String currentTransport = factString(trustedFacts, "transportProtocol");
            if (isNotApplicable(currentTransport)) {
                log.debug("传输协议限制不适用于当前检查");
            } else if (!StringUtils.hasText(currentTransport) || isUnknownFact(currentTransport)) {
                return "传输协议合规事实不可用: " + safeFact(currentTransport);
            } else if (!transportProtocol.equalsIgnoreCase(currentTransport.trim())) {
                return "传输协议不符合要求: " + currentTransport;
            }
        }

        String storageAlgorithm = stringValue(config.get("storageAlgorithm"));
        if (Boolean.TRUE.equals(config.get("encryptStorage")) && StringUtils.hasText(storageAlgorithm)) {
            String currentStorageAlgorithm = factString(trustedFacts, "storageAlgorithm");
            if (isNotApplicable(currentStorageAlgorithm)) {
                log.debug("存储算法限制不适用于当前检查");
            } else if (!StringUtils.hasText(currentStorageAlgorithm) || isUnknownFact(currentStorageAlgorithm)) {
                return "存储加密合规事实不可用: " + safeFact(currentStorageAlgorithm);
            } else if (!storageAlgorithm.equalsIgnoreCase(currentStorageAlgorithm.trim())) {
                return "存储加密算法不符合要求: " + currentStorageAlgorithm;
            }
        }

        Number sessionTtlMinutes = numberValue(config.get("sessionTtlMinutes"));
        Number currentSessionTtl = factNumber(trustedFacts, "sessionTtlMinutes");
        if (sessionTtlMinutes != null && currentSessionTtl == null) {
            return "会话时长合规事实不可用";
        }
        if (sessionTtlMinutes != null && currentSessionTtl.intValue() > sessionTtlMinutes.intValue()) {
            return "会话时长超出限制: " + currentSessionTtl;
        }

        Number maxSessions = numberValue(config.get("maxSessions"));
        Number currentSessionCount = factNumber(trustedFacts, "activeSessions");
        if (maxSessions != null && currentSessionCount == null) {
            return "活跃会话合规事实不可用";
        }
        if (maxSessions != null && currentSessionCount.intValue() > maxSessions.intValue()) {
            return "活跃会话数超出限制: " + currentSessionCount;
        }

        Number tenantQps = numberValue(config.get("tenantQps"));
        Number currentQps = factNumber(trustedFacts, "tenantQps");
        if (tenantQps != null && currentQps == null) {
            return "租户请求速率事实不可用";
        }
        if (tenantQps != null && currentQps.intValue() > tenantQps.intValue()) {
            return "租户请求速率超限: " + currentQps;
        }

        Number userDailyQuota = numberValue(config.get("userDailyQuota"));
        Number currentDailyUsage = factNumber(trustedFacts, "userDailyUsage");
        if (userDailyQuota != null && currentDailyUsage == null) {
            return "用户日配额事实不可用";
        }
        if (userDailyQuota != null && currentDailyUsage.intValue() > userDailyQuota.intValue()) {
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

    private boolean appliesLengthLimit(SecurityPolicy policy, SecurityRuntimeCheckRequest request) {
        String scene = request.getScene();
        if (!SecurityComplianceConstant.CHECK_SCENE_OUTPUT.equals(scene)
                && !SecurityComplianceConstant.CHECK_SCENE_TOOL_POST.equals(scene)) {
            return true;
        }
        // 只有明确限于输入的控制点才收窄长度规则，兼容未标注范围的旧策略。
        List<String> points = stringList(policy.getControlPoints());
        return points.isEmpty() || !points.stream().allMatch(INPUT_LENGTH_CONTROL_POINTS::contains);
    }

    private boolean matchesScene(SecurityPolicy policy, String scene) {
        List<String> points = stringList(policy.getControlPoints());
        // 保留旧 input 等控制点的内容检查行为，精确运行时阶段按声明匹配。
        return points.isEmpty() || !points.stream().allMatch(RUNTIME_SCENES::contains) || points.contains(scene);
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
                                  String traceId,
                                  String resourceType,
                                  Map<String, Object> trustedFacts) {
        String eventStatus = switch (result.getDecision()) {
            case SecurityComplianceConstant.DECISION_BLOCK -> SecurityComplianceConstant.AUDIT_STATUS_BLOCKED;
            case SecurityComplianceConstant.DECISION_PAUSE, SecurityComplianceConstant.DECISION_WARN ->
                    SecurityComplianceConstant.AUDIT_STATUS_WARNING;
            default -> SecurityComplianceConstant.AUDIT_STATUS_SUCCESS;
        };
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scene", safeValue(request.getScene()));
        payload.put("decision", result.getDecision());
        payload.put("incidentId", safeValue(result.getIncidentId()));
        payload.put("matchedPolicies", result.getMatchedPolicies() == null ? List.of() : result.getMatchedPolicies());
        payload.put("matchedRulePacks", result.getMatchedRulePacks() == null ? List.of() : result.getMatchedRulePacks());
        payload.put("resourceName", safeValue(request.getResourceName()));
        payload.put("factSource", "server");
        payload.put("trustedFacts", trustedFacts);
        payload.put("guardPhase", contextValue(request, "guardPhase"));
        payload.put("contentSha256", contextValue(request, "contentSha256"));
        payload.put("displayContentSha256", contextValue(request, "displayContentSha256"));
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
                resourceType,
                request.getResourceId(),
                "运行时安全检查",
                result.getMessage(),
                payload,
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

    private Map<String, Object> resolveTrustedFacts(SecurityRuntimeCheckRequest request, String tenantId) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("region", normalizeFact(trustedRegion));
        facts.put("transportProtocol", normalizeFact(trustedTransportProtocol));
        facts.put("storageAlgorithm", normalizeFact(trustedStorageAlgorithm));
        facts.put("sessionTtlMinutes", Math.max(trustedSessionTtlMinutes, 0));
        facts.put("activeSessions", Math.max(trustedActiveSessions, 0));
        boolean usageBoundary = request != null && USAGE_BOUNDARY_SCENES.contains(request.getScene());
        facts.put("tenantQps", usageBoundary ? currentQps(tenantId) : peekQps(tenantId));
        facts.put("userDailyUsage", usageBoundary
                ? currentDailyUsage(tenantId, SecurityUtil.getCurrentUserId())
                : peekDailyUsage(tenantId, SecurityUtil.getCurrentUserId()));
        return facts;
    }

    private String normalizeFact(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase() : "unknown";
    }

    private int currentQps(String tenantId) {
        long epochSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        WindowCounter counter = qpsWindows.computeIfAbsent(tenantId, ignored -> new WindowCounter());
        return counter.increment(epochSecond);
    }

    private int peekQps(String tenantId) {
        WindowCounter counter = qpsWindows.get(tenantId);
        return counter == null ? 0 : counter.peek(LocalDateTime.now().toEpochSecond(ZoneOffset.UTC));
    }

    private int currentDailyUsage(String tenantId, String userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        String key = safeValue(tenantId) + ":" + safeValue(userId);
        DailyCounter counter = dailyUsage.computeIfAbsent(key, ignored -> new DailyCounter());
        return counter.increment(today);
    }

    private int peekDailyUsage(String tenantId, String userId) {
        DailyCounter counter = dailyUsage.get(safeValue(tenantId) + ":" + safeValue(userId));
        return counter == null ? 0 : counter.peek(LocalDate.now(ZoneOffset.UTC));
    }

    private String factString(Map<String, Object> trustedFacts, String key) {
        Object value = trustedFacts.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Number factNumber(Map<String, Object> trustedFacts, String key) {
        return numberValue(trustedFacts.get(key));
    }

    private boolean isUnknownFact(String value) {
        return !StringUtils.hasText(value)
                || "unknown".equalsIgnoreCase(value)
                || "missing".equalsIgnoreCase(value);
    }

    private boolean isNotApplicable(String value) {
        return "not_applicable".equalsIgnoreCase(value)
                || "not-applicable".equalsIgnoreCase(value)
                || "na".equalsIgnoreCase(value);
    }

    private String safeFact(String value) {
        return StringUtils.hasText(value) ? value : "missing";
    }

    private static final class WindowCounter {
        private long epochSecond = -1;
        private final AtomicInteger count = new AtomicInteger();

        private synchronized int increment(long currentSecond) {
            if (epochSecond != currentSecond) {
                epochSecond = currentSecond;
                count.set(0);
            }
            return count.incrementAndGet();
        }

        private synchronized int peek(long currentSecond) {
            return epochSecond == currentSecond ? count.get() : 0;
        }
    }

    private static final class DailyCounter {
        private LocalDate day;
        private final AtomicInteger count = new AtomicInteger();

        private synchronized int increment(LocalDate currentDay) {
            if (!currentDay.equals(day)) {
                day = currentDay;
                count.set(0);
            }
            return count.incrementAndGet();
        }

        private synchronized int peek(LocalDate currentDay) {
            return currentDay.equals(day) ? count.get() : 0;
        }
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

    private String contextValue(SecurityRuntimeCheckRequest request, String key) {
        if (request == null || request.getContext() == null) {
            return "";
        }
        Object value = request.getContext().get(key);
        return value == null ? "" : String.valueOf(value);
    }
}
