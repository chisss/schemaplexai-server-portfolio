package com.schemaplexai.web.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecurityBindingMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentActionMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.SecurityPolicyMapper;
import com.schemaplexai.dao.mapper.SecurityPolicyVersionMapper;
import com.schemaplexai.dao.mapper.SecurityRuleItemMapper;
import com.schemaplexai.dao.mapper.SecurityRulePackMapper;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.model.entity.SecurityBinding;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.model.entity.SecurityIncidentAction;
import com.schemaplexai.model.entity.SecurityPolicy;
import com.schemaplexai.model.entity.SecurityPolicyVersion;
import com.schemaplexai.model.entity.SecurityRuleItem;
import com.schemaplexai.model.entity.SecurityRulePack;
import com.schemaplexai.service.security.SecurityAuditEventService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全合规模块默认数据初始化
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityComplianceDataInitializer implements CommandLineRunner {

    private final TenantMapper tenantMapper;
    private final SecurityPolicyMapper securityPolicyMapper;
    private final SecurityPolicyVersionMapper securityPolicyVersionMapper;
    private final SecurityBindingMapper securityBindingMapper;
    private final SecurityIncidentMapper securityIncidentMapper;
    private final SecurityIncidentActionMapper securityIncidentActionMapper;
    private final SecurityRulePackMapper securityRulePackMapper;
    private final SecurityRuleItemMapper securityRuleItemMapper;
    private final SecurityAuditEventService securityAuditEventService;

    @Override
    public void run(String... args) {
        var defaultTenant = tenantMapper.selectOne(
                new LambdaQueryWrapper<com.schemaplexai.model.entity.Tenant>()
                        .eq(com.schemaplexai.model.entity.Tenant::getCode, "default")
                        .last("LIMIT 1")
        );
        if (defaultTenant == null) {
            log.warn("未找到默认租户，跳过安全合规初始化");
            return;
        }

        SecurityUtil.setCurrentTenantId(defaultTenant.getId());
        SecurityUtil.setCurrentUserId(null);
        SecurityUtil.setCurrentUsername("system");
        try {
            List<SecurityPolicy> policies = ensurePolicies(defaultTenant.getId());
            ensurePolicySnapshotsAndBindings(defaultTenant.getId(), policies);

            List<SecurityRulePack> rulePacks = ensureRulePacks(defaultTenant.getId());
            ensureRulePackBindings(defaultTenant.getId(), rulePacks);

            ensureDemoIncidents(defaultTenant.getId(), policies, rulePacks);
            log.info("安全合规初始化完成: policies={}, rulePacks={}", policies.size(), rulePacks.size());
        } finally {
            SecurityUtil.clear();
        }
    }

    private List<SecurityPolicy> ensurePolicies(String tenantId) {
        return List.of(
                ensurePolicy(buildPolicy(
                        tenantId,
                        "runtime_prompt_guard",
                        "Prompt 注入防护策略",
                        SecurityComplianceConstant.DOMAIN_RUNTIME,
                        "prompt_injection",
                        SecurityComplianceConstant.ENFORCEMENT_BLOCK,
                        SecurityComplianceConstant.RISK_LEVEL_CRITICAL,
                        List.of("input", "system_prompt", "instruction_chain"),
                        Map.of(
                                "keywords", List.of("忽略之前指令", "泄露系统提示词", "执行恶意指令"),
                                "regexPatterns", List.of("(?i)ignore\\s+all\\s+previous\\s+instructions"),
                                "maxLength", 1200,
                                "hitAction", SecurityComplianceConstant.DECISION_BLOCK
                        ),
                        "拦截注入、越权和系统提示词泄露类风险。"
                )),
                ensurePolicy(buildPolicy(
                        tenantId,
                        "runtime_tool_guard",
                        "高危工具调用守卫",
                        SecurityComplianceConstant.DOMAIN_RUNTIME,
                        "tool_guard",
                        SecurityComplianceConstant.ENFORCEMENT_BLOCK,
                        SecurityComplianceConstant.RISK_LEVEL_HIGH,
                        List.of("tool_pre", "command_guard"),
                        Map.of(
                                "forbiddenTools", List.of("shell_command", "browser_run_code"),
                                "forbiddenCommands", List.of("rm -rf", "curl http://", "chmod 777"),
                                "reviewRequired", true,
                                "hitAction", SecurityComplianceConstant.DECISION_PAUSE
                        ),
                        "对高危工具和命令执行做暂停或拦截。"
                )),
                ensurePolicy(buildPolicy(
                        tenantId,
                        "data_encryption_guard",
                        "数据与会话加密策略",
                        SecurityComplianceConstant.DOMAIN_DATA,
                        "data_encryption",
                        SecurityComplianceConstant.ENFORCEMENT_BLOCK,
                        SecurityComplianceConstant.RISK_LEVEL_HIGH,
                        List.of("transport", "storage", "session"),
                        Map.of(
                                "transportProtocol", "TLS1.3",
                                "storageAlgorithm", "AES-256",
                                "encryptStorage", true,
                                "encryptTransport", true,
                                "sessionTtlMinutes", 60,
                                "maxSessions", 3
                        ),
                        "约束传输、存储与会话数据的最小安全基线。"
                )),
                ensurePolicy(buildPolicy(
                        tenantId,
                        "data_memory_write_guard",
                        "知识回写与会话留存策略",
                        SecurityComplianceConstant.DOMAIN_DATA,
                        "knowledge_review",
                        SecurityComplianceConstant.ENFORCEMENT_WARN,
                        SecurityComplianceConstant.RISK_LEVEL_MEDIUM,
                        List.of("memory_write", "masked_output"),
                        Map.of(
                                "forbidMemoryWrite", true,
                                "requireMaskedOutput", true,
                                "allowedRegions", List.of("cn-hz", "cn-sh")
                        ),
                        "对知识库回写和会话恢复做额外约束。"
                )),
                ensurePolicy(buildPolicy(
                        tenantId,
                        "risk_rate_limit",
                        "访问限流与异常行为策略",
                        SecurityComplianceConstant.DOMAIN_RISK,
                        "rate_limit",
                        SecurityComplianceConstant.ENFORCEMENT_WARN,
                        SecurityComplianceConstant.RISK_LEVEL_HIGH,
                        List.of("qps", "abuse_detection", "ip_reputation"),
                        Map.of(
                                "tenantQps", 20,
                                "userDailyQuota", 500,
                                "temporaryBanMinutes", 30,
                                "anomalyThreshold", 3,
                                "autoEscalate", true
                        ),
                        "对恶意刷屏、批量攻击和异常调用行为进行识别与限流。"
                )),
                ensurePolicy(buildPolicy(
                        tenantId,
                        "risk_audit_retention",
                        "审计链路留痕策略",
                        SecurityComplianceConstant.DOMAIN_RISK,
                        "audit_trace",
                        SecurityComplianceConstant.ENFORCEMENT_WARN,
                        SecurityComplianceConstant.RISK_LEVEL_MEDIUM,
                        List.of("trace", "retention"),
                        Map.of(
                                "auditRetentionDays", 180,
                                "highRiskTags", List.of("p0", "p1", "high_sensitive")
                        ),
                        "确保主流程命中安全检查后可追踪、可回溯。"
                )),
                ensurePolicy(buildPolicy(
                        tenantId,
                        "industry_finance_pack",
                        "金融行业合规基线策略",
                        SecurityComplianceConstant.DOMAIN_INDUSTRY,
                        "industry_pack",
                        SecurityComplianceConstant.ENFORCEMENT_BLOCK,
                        SecurityComplianceConstant.RISK_LEVEL_HIGH,
                        List.of("financial_statement", "license_scope", "channel_publish"),
                        Map.of(
                                "keywords", List.of("保本收益", "稳赚不赔"),
                                "reviewRequired", true,
                                "hitAction", SecurityComplianceConstant.DECISION_PAUSE
                        ),
                        "用于金融场景下的输出承诺校验与资质边界约束。"
                ))
        );
    }

    private SecurityPolicy ensurePolicy(SecurityPolicy seed) {
        SecurityPolicy existing = securityPolicyMapper.selectOne(
                new LambdaQueryWrapper<SecurityPolicy>()
                        .eq(SecurityPolicy::getTenantId, seed.getTenantId())
                        .eq(SecurityPolicy::getPolicyCode, seed.getPolicyCode())
                        .last("LIMIT 1")
        );
        if (existing != null) {
            return existing;
        }
        securityPolicyMapper.insert(seed);
        securityAuditEventService.recordPolicyEvent(
                seed,
                SecurityComplianceConstant.EVENT_POLICY_BOOTSTRAPPED,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                "初始化内置安全策略",
                "系统初始化内置安全策略 " + seed.getPolicyName(),
                Map.of("bootstrap", true, "policyCode", seed.getPolicyCode()),
                null
        );
        return seed;
    }

    private void ensurePolicySnapshotsAndBindings(String tenantId, List<SecurityPolicy> policies) {
        for (SecurityPolicy policy : policies) {
            ensurePolicyVersion(tenantId, policy);
            ensureTenantBinding(tenantId,
                    SecurityComplianceConstant.SOURCE_TYPE_POLICY,
                    policy.getId(),
                    policy.getPolicyCode(),
                    policy.getPolicyName(),
                    policy.getDomainCode());
        }
    }

    private void ensurePolicyVersion(String tenantId, SecurityPolicy policy) {
        Integer versionNo = policy.getVersion() == null || policy.getVersion() <= 0 ? 1 : policy.getVersion();
        Long count = securityPolicyVersionMapper.selectCount(
                new LambdaQueryWrapper<SecurityPolicyVersion>()
                        .eq(SecurityPolicyVersion::getTenantId, tenantId)
                        .eq(SecurityPolicyVersion::getPolicyId, policy.getId())
                        .eq(SecurityPolicyVersion::getVersionNo, versionNo)
        );
        if (count != null && count > 0) {
            return;
        }
        SecurityPolicyVersion version = new SecurityPolicyVersion();
        fillBase(version, tenantId);
        version.setPolicyId(policy.getId());
        version.setVersionNo(versionNo);
        version.setSnapshotConfig(policy.getPolicyConfig());
        version.setSnapshotTargets(policy.getTargetSelector());
        version.setChangeSummary("系统初始化发布快照");
        version.setPublishedBy(null);
        version.setPublishedAt(policy.getLastPublishedAt() == null ? LocalDateTime.now() : policy.getLastPublishedAt());
        securityPolicyVersionMapper.insert(version);
    }

    private List<SecurityRulePack> ensureRulePacks(String tenantId) {
        return List.of(
                ensureRulePack(buildRulePack(
                        tenantId,
                        "finance_advertising_pack",
                        "金融宣传合规规则包",
                        "finance",
                        SecurityComplianceConstant.DECISION_BLOCK,
                        Map.of(
                                "businessLine", "wealth",
                                "regulators", List.of("CSRC", "CBIRC"),
                                "reviewOwner", "合规专员",
                                "evidenceRequired", true
                        ),
                        "控制金融场景中的收益承诺、牌照越界和高风险宣传表述。"
                ), List.of(
                        buildRuleItem(tenantId, "FINANCE_PROMISE", "禁止收益承诺", "《金融广告管理办法》", "不得承诺固定收益",
                                SecurityComplianceConstant.RISK_LEVEL_CRITICAL, SecurityComplianceConstant.DECISION_BLOCK, "contains", "保本收益", 10),
                        buildRuleItem(tenantId, "FINANCE_LICENSE", "牌照边界提示", "《证券法》", "超出牌照范围必须提示人工顾问",
                                SecurityComplianceConstant.RISK_LEVEL_HIGH, SecurityComplianceConstant.DECISION_PAUSE, "contains", "代客理财", 20)
                )),
                ensureRulePack(buildRulePack(
                        tenantId,
                        "medical_consult_pack",
                        "医疗咨询合规规则包",
                        "medical",
                        SecurityComplianceConstant.DECISION_PAUSE,
                        Map.of(
                                "businessLine", "ai_consult",
                                "regulators", List.of("NHC", "NMPA"),
                                "reviewOwner", "医疗质控负责人",
                                "evidenceRequired", true
                        ),
                        "控制医疗问答中的确诊、处方和免责边界。"
                ), List.of(
                        buildRuleItem(tenantId, "MEDICAL_DIAGNOSIS", "禁止直接确诊", "《互联网诊疗监管细则》", "不得直接给出确诊结论",
                                SecurityComplianceConstant.RISK_LEVEL_CRITICAL, SecurityComplianceConstant.DECISION_PAUSE, "contains", "确诊为", 10),
                        buildRuleItem(tenantId, "MEDICAL_DRUG", "禁止直接开药", "《处方管理办法》", "不得直接提供处方建议",
                                SecurityComplianceConstant.RISK_LEVEL_HIGH, SecurityComplianceConstant.DECISION_BLOCK, "contains", "建议服用处方药", 20)
                ))
        );
    }

    private SecurityRulePack ensureRulePack(SecurityRulePack seed, List<SecurityRuleItem> items) {
        SecurityRulePack existing = securityRulePackMapper.selectOne(
                new LambdaQueryWrapper<SecurityRulePack>()
                        .eq(SecurityRulePack::getTenantId, seed.getTenantId())
                        .eq(SecurityRulePack::getPackCode, seed.getPackCode())
                        .last("LIMIT 1")
        );
        SecurityRulePack target = existing == null ? seed : existing;
        if (existing == null) {
            securityRulePackMapper.insert(seed);
            securityAuditEventService.recordEvent(
                    seed.getTenantId(),
                    null,
                    SecurityComplianceConstant.EVENT_POLICY_BOOTSTRAPPED,
                    SecurityComplianceConstant.AUDIT_SOURCE_RULE_PACK,
                    SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                    SecurityComplianceConstant.RISK_LEVEL_MEDIUM,
                    SecurityComplianceConstant.DOMAIN_INDUSTRY,
                    null,
                    null,
                    SecurityComplianceConstant.RESOURCE_TYPE_RULE_PACK,
                    seed.getId(),
                    "初始化行业规则包",
                    "系统初始化行业规则包 " + seed.getPackName(),
                    Map.of("packCode", seed.getPackCode(), "bootstrap", true),
                    null
            );
        }

        Long itemCount = securityRuleItemMapper.selectCount(
                new LambdaQueryWrapper<SecurityRuleItem>()
                        .eq(SecurityRuleItem::getTenantId, target.getTenantId())
                        .eq(SecurityRuleItem::getPackId, target.getId())
        );
        if (itemCount == null || itemCount == 0) {
            for (SecurityRuleItem item : items) {
                item.setPackId(target.getId());
                securityRuleItemMapper.insert(item);
            }
        }
        return target;
    }

    private void ensureRulePackBindings(String tenantId, List<SecurityRulePack> rulePacks) {
        for (SecurityRulePack rulePack : rulePacks) {
            ensureTenantBinding(tenantId,
                    SecurityComplianceConstant.SOURCE_TYPE_RULE_PACK,
                    rulePack.getId(),
                    rulePack.getPackCode(),
                    rulePack.getPackName(),
                    SecurityComplianceConstant.DOMAIN_INDUSTRY);
        }
    }

    private void ensureDemoIncidents(String tenantId, List<SecurityPolicy> policies, List<SecurityRulePack> rulePacks) {
        Map<String, SecurityPolicy> policyMap = new LinkedHashMap<>();
        for (SecurityPolicy policy : policies) {
            policyMap.put(policy.getPolicyCode(), policy);
        }
        Map<String, SecurityRulePack> packMap = new LinkedHashMap<>();
        for (SecurityRulePack pack : rulePacks) {
            packMap.put(pack.getPackCode(), pack);
        }

        ensureIncident(buildIncident(
                tenantId,
                "SIR-DEMO-0001",
                "trace-runtime-blocked-001",
                SecurityComplianceConstant.DOMAIN_RUNTIME,
                SecurityComplianceConstant.RISK_LEVEL_HIGH,
                SecurityComplianceConstant.INCIDENT_STATUS_INVESTIGATING,
                SecurityComplianceConstant.SOURCE_TYPE_POLICY,
                policyMap.get("runtime_tool_guard").getId(),
                policyMap.get("runtime_tool_guard").getPolicyName(),
                policyMap.get("runtime_tool_guard").getId(),
                policyMap.get("runtime_tool_guard").getPolicyCode(),
                SecurityComplianceConstant.DECISION_PAUSE,
                "运行时命中高危工具策略",
                "用户请求包含高危工具 shell_command，执行已暂停等待人工确认。",
                Map.of(
                        "userActionTip", "请移除高危工具或提交人工审批。",
                        "adminActionTip", "确认是否允许本次高危工具调用。"
                )
        ), List.of(
                buildIncidentAction(tenantId, SecurityComplianceConstant.ACTION_COMMENT, "created", "系统自动创建安全事件"),
                buildIncidentAction(tenantId, SecurityComplianceConstant.ACTION_ASSIGN, "success", "已指派给安全值班负责人处理")
        ));

        ensureIncident(buildIncident(
                tenantId,
                "SIR-DEMO-0002",
                "trace-risk-warning-001",
                SecurityComplianceConstant.DOMAIN_RISK,
                SecurityComplianceConstant.RISK_LEVEL_MEDIUM,
                SecurityComplianceConstant.INCIDENT_STATUS_NEW,
                SecurityComplianceConstant.SOURCE_TYPE_POLICY,
                policyMap.get("risk_rate_limit").getId(),
                policyMap.get("risk_rate_limit").getPolicyName(),
                policyMap.get("risk_rate_limit").getId(),
                policyMap.get("risk_rate_limit").getPolicyCode(),
                SecurityComplianceConstant.DECISION_WARN,
                "租户请求频率接近上限",
                "同一租户在短时间内请求量激增，已触发限流预警。",
                Map.of(
                        "userActionTip", "请稍后重试或降低批量调用频率。",
                        "adminActionTip", "检查是否存在批量刷调用或异常脚本。"
                )
        ), List.of(
                buildIncidentAction(tenantId, SecurityComplianceConstant.ACTION_COMMENT, "created", "系统自动创建风控预警")
        ));

        ensureIncident(buildIncident(
                tenantId,
                "SIR-DEMO-0003",
                "trace-industry-resolved-001",
                SecurityComplianceConstant.DOMAIN_INDUSTRY,
                SecurityComplianceConstant.RISK_LEVEL_HIGH,
                SecurityComplianceConstant.INCIDENT_STATUS_RESOLVED,
                SecurityComplianceConstant.SOURCE_TYPE_RULE_PACK,
                packMap.get("finance_advertising_pack").getId(),
                packMap.get("finance_advertising_pack").getPackName(),
                policyMap.get("industry_finance_pack").getId(),
                policyMap.get("industry_finance_pack").getPolicyCode(),
                SecurityComplianceConstant.DECISION_BLOCK,
                "金融宣传内容命中收益承诺条款",
                "输出中包含“保本收益”表述，已被行业规则包拦截并完成整改。",
                Map.of(
                        "userActionTip", "请使用风险揭示后的合规表述重新生成。",
                        "adminActionTip", "复查渠道模板是否包含历史违规文案。"
                )
        ), List.of(
                buildIncidentAction(tenantId, SecurityComplianceConstant.ACTION_COMMENT, "created", "系统自动创建合规事件"),
                buildIncidentAction(tenantId, SecurityComplianceConstant.ACTION_RESOLVE, "success", "已替换违规宣传语并重新发布")
        ));
    }

    private void ensureIncident(SecurityIncident seed, List<SecurityIncidentAction> actions) {
        SecurityIncident existing = securityIncidentMapper.selectOne(
                new LambdaQueryWrapper<SecurityIncident>()
                        .eq(SecurityIncident::getTenantId, seed.getTenantId())
                        .eq(SecurityIncident::getIncidentNo, seed.getIncidentNo())
                        .last("LIMIT 1")
        );
        SecurityIncident target = existing == null ? seed : existing;
        if (existing == null) {
            securityIncidentMapper.insert(seed);
            recordIncidentAudit(seed);
        }
        Long actionCount = securityIncidentActionMapper.selectCount(
                new LambdaQueryWrapper<SecurityIncidentAction>()
                        .eq(SecurityIncidentAction::getTenantId, target.getTenantId())
                        .eq(SecurityIncidentAction::getIncidentId, target.getId())
        );
        if (actionCount == null || actionCount == 0) {
            for (SecurityIncidentAction action : actions) {
                action.setIncidentId(target.getId());
                securityIncidentActionMapper.insert(action);
            }
        }
    }

    private void recordIncidentAudit(SecurityIncident incident) {
        String eventType = SecurityComplianceConstant.DECISION_BLOCK.equals(incident.getDecision())
                ? SecurityComplianceConstant.EVENT_RUNTIME_BLOCKED
                : SecurityComplianceConstant.DECISION_PAUSE.equals(incident.getDecision())
                ? SecurityComplianceConstant.EVENT_RUNTIME_PAUSED
                : SecurityComplianceConstant.EVENT_RUNTIME_WARNING;
        String auditStatus = SecurityComplianceConstant.DECISION_BLOCK.equals(incident.getDecision())
                ? SecurityComplianceConstant.AUDIT_STATUS_BLOCKED
                : SecurityComplianceConstant.AUDIT_STATUS_WARNING;
        securityAuditEventService.recordEvent(
                incident.getTenantId(),
                incident.getTraceId(),
                eventType,
                SecurityComplianceConstant.AUDIT_SOURCE_RUNTIME_ENGINE,
                auditStatus,
                incident.getRiskLevel(),
                incident.getDomainCode(),
                incident.getPolicyId(),
                incident.getPolicyCode(),
                SecurityComplianceConstant.RESOURCE_TYPE_INCIDENT,
                incident.getId(),
                incident.getEventTitle(),
                incident.getEventDetail(),
                incident.getPayload(),
                null
        );
    }

    private void ensureTenantBinding(String tenantId,
                                     String sourceType,
                                     String sourceId,
                                     String sourceCode,
                                     String sourceName,
                                     String domainCode) {
        Long count = securityBindingMapper.selectCount(
                new LambdaQueryWrapper<SecurityBinding>()
                        .eq(SecurityBinding::getTenantId, tenantId)
                        .eq(SecurityBinding::getSourceType, sourceType)
                        .eq(SecurityBinding::getSourceId, sourceId)
        );
        if (count != null && count > 0) {
            return;
        }
        SecurityBinding binding = new SecurityBinding();
        fillBase(binding, tenantId);
        binding.setSourceType(sourceType);
        binding.setSourceId(sourceId);
        binding.setSourceCode(sourceCode);
        binding.setSourceName(sourceName);
        binding.setBindingType(SecurityComplianceConstant.SCOPE_TENANT);
        binding.setTargetId(null);
        binding.setTargetName("当前租户");
        binding.setDomainCode(domainCode);
        binding.setStatus(SecurityComplianceConstant.STATUS_ACTIVE);
        binding.setPriority(10);
        binding.setBindingConfig(Map.of("bootstrap", true));
        securityBindingMapper.insert(binding);
    }

    private SecurityPolicy buildPolicy(String tenantId,
                                       String policyCode,
                                       String policyName,
                                       String domainCode,
                                       String policyType,
                                       String enforcementMode,
                                       String riskLevel,
                                       List<String> controlPoints,
                                       Map<String, Object> policyConfig,
                                       String description) {
        LocalDateTime now = LocalDateTime.now();
        SecurityPolicy policy = new SecurityPolicy();
        fillBase(policy, tenantId);
        policy.setPolicyCode(policyCode);
        policy.setPolicyName(policyName);
        policy.setDomainCode(domainCode);
        policy.setPolicyType(policyType);
        policy.setPolicyScope(SecurityComplianceConstant.SCOPE_TENANT);
        policy.setStatus(SecurityComplianceConstant.STATUS_ACTIVE);
        policy.setEnforcementMode(enforcementMode);
        policy.setRiskLevel(riskLevel);
        policy.setVersion(1);
        policy.setIsBuiltin(true);
        policy.setControlPoints(controlPoints);
        policy.setTags(List.of(domainCode, riskLevel));
        policy.setTargetSelector(Map.of("scope", SecurityComplianceConstant.SCOPE_TENANT, "tenantScope", "current"));
        policy.setPolicyConfig(policyConfig);
        policy.setDescription(description);
        policy.setLastPublishedAt(now);
        return policy;
    }

    private SecurityRulePack buildRulePack(String tenantId,
                                           String packCode,
                                           String packName,
                                           String industryCode,
                                           String defaultAction,
                                           Map<String, Object> packConfig,
                                           String description) {
        SecurityRulePack rulePack = new SecurityRulePack();
        fillBase(rulePack, tenantId);
        rulePack.setPackCode(packCode);
        rulePack.setPackName(packName);
        rulePack.setIndustryCode(industryCode);
        rulePack.setStatus(SecurityComplianceConstant.STATUS_ACTIVE);
        rulePack.setPackVersion(1);
        rulePack.setDefaultAction(defaultAction);
        rulePack.setIsBuiltin(true);
        rulePack.setPackConfig(packConfig);
        rulePack.setDescription(description);
        return rulePack;
    }

    private SecurityRuleItem buildRuleItem(String tenantId,
                                           String itemCode,
                                           String itemName,
                                           String ruleSource,
                                           String ruleClause,
                                           String riskLevel,
                                           String action,
                                           String matchType,
                                           String matchContent,
                                           int sortOrder) {
        SecurityRuleItem item = new SecurityRuleItem();
        fillBase(item, tenantId);
        item.setItemCode(itemCode);
        item.setItemName(itemName);
        item.setRuleSource(ruleSource);
        item.setRuleClause(ruleClause);
        item.setRiskLevel(riskLevel);
        item.setAction(action);
        item.setMatchType(matchType);
        item.setMatchContent(matchContent);
        item.setSortOrder(sortOrder);
        item.setEnabled(true);
        item.setItemConfig(Map.of("bootstrap", true));
        return item;
    }

    private SecurityIncident buildIncident(String tenantId,
                                           String incidentNo,
                                           String traceId,
                                           String domainCode,
                                           String riskLevel,
                                           String status,
                                           String sourceType,
                                           String sourceId,
                                           String sourceName,
                                           String policyId,
                                           String policyCode,
                                           String decision,
                                           String eventTitle,
                                           String eventDetail,
                                           Map<String, Object> payload) {
        SecurityIncident incident = new SecurityIncident();
        fillBase(incident, tenantId);
        incident.setIncidentNo(incidentNo);
        incident.setTraceId(traceId);
        incident.setDomainCode(domainCode);
        incident.setRiskLevel(riskLevel);
        incident.setStatus(status);
        incident.setSourceType(sourceType);
        incident.setSourceId(sourceId);
        incident.setSourceName(sourceName);
        incident.setPolicyId(policyId);
        incident.setPolicyCode(policyCode);
        incident.setDecision(decision);
        incident.setEventTitle(eventTitle);
        incident.setEventDetail(eventDetail);
        incident.setPayload(payload);
        if (SecurityComplianceConstant.INCIDENT_STATUS_RESOLVED.equals(status)) {
            incident.setResolvedAt(LocalDateTime.now().minusHours(2));
            incident.setResolutionSummary("初始化示例：已完成整改");
        }
        return incident;
    }

    private SecurityIncidentAction buildIncidentAction(String tenantId,
                                                       String actionType,
                                                       String actionResult,
                                                       String comment) {
        SecurityIncidentAction action = new SecurityIncidentAction();
        fillBase(action, tenantId);
        action.setActionType(actionType);
        action.setActionResult(actionResult);
        action.setComment(comment);
        action.setMetadata(Map.of("bootstrap", true));
        action.setOperatorId(null);
        action.setOperatorName("system");
        action.setActionAt(LocalDateTime.now().minusMinutes(10));
        return action;
    }

    private void fillBase(com.schemaplexai.model.entity.BaseEntity entity, String tenantId) {
        LocalDateTime now = LocalDateTime.now();
        entity.setTenantId(tenantId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setCreatedBy(null);
        entity.setUpdatedBy(null);
        entity.setDeleted(0);
    }

    @PreDestroy
    public void clearContext() {
        SecurityUtil.clear();
    }
}
