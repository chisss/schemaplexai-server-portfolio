package com.schemaplexai.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecurityAuditEventMapper;
import com.schemaplexai.dao.mapper.SecurityBindingMapper;
import com.schemaplexai.dao.mapper.SecurityIncidentMapper;
import com.schemaplexai.dao.mapper.SecurityPolicyMapper;
import com.schemaplexai.dao.mapper.SecurityPolicyVersionMapper;
import com.schemaplexai.dao.mapper.SecurityRulePackMapper;
import com.schemaplexai.model.converter.SecurityPolicyConverter;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityBindingSaveRequest;
import com.schemaplexai.model.dto.security.SecurityPolicyQueryRequest;
import com.schemaplexai.model.dto.security.SecurityPolicySaveRequest;
import com.schemaplexai.model.dto.security.SecurityPolicyToggleRequest;
import com.schemaplexai.model.entity.SecurityAuditEvent;
import com.schemaplexai.model.entity.SecurityBinding;
import com.schemaplexai.model.entity.SecurityIncident;
import com.schemaplexai.model.entity.SecurityPolicy;
import com.schemaplexai.model.entity.SecurityPolicyVersion;
import com.schemaplexai.model.entity.SecurityRulePack;
import com.schemaplexai.model.vo.agent.AgentVO;
import com.schemaplexai.model.vo.security.SecurityBindingVO;
import com.schemaplexai.model.vo.security.SecurityDomainOverviewVO;
import com.schemaplexai.model.vo.security.SecurityIncidentVO;
import com.schemaplexai.model.vo.security.SecurityOverviewVO;
import com.schemaplexai.model.vo.security.SecurityPolicyVO;
import com.schemaplexai.model.vo.security.SecurityPolicyVersionVO;
import com.schemaplexai.model.vo.security.SecurityTargetOptionVO;
import com.schemaplexai.model.vo.security.SecurityTargetOptionsVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateVO;
import com.schemaplexai.model.vo.workspace.WorkspaceVO;
import com.schemaplexai.service.agent.AgentService;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.security.SecurityComplianceService;
import com.schemaplexai.service.workflow.WorkflowTemplateService;
import com.schemaplexai.service.workspace.WorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 安全合规服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityComplianceServiceImpl implements SecurityComplianceService {

    private static final String TARGET_SCOPE_KEY = "scope";
    private static final String TARGET_TENANT_SCOPE_KEY = "tenantScope";
    private static final String TARGET_AGENT_IDS_KEY = "agentIds";
    private static final String TARGET_WORKFLOW_TEMPLATE_IDS_KEY = "workflowTemplateIds";
    private static final String TARGET_WORKSPACE_IDS_KEY = "workspaceIds";
    private static final String TARGET_RESOLVED_TARGETS_KEY = "resolvedTargets";
    private static final String TARGET_COUNT_KEY = "targetCount";
    private static final String TARGET_CURRENT_VALUE = "current";
    private static final String TARGET_READY_STATUS = "ready";

    private final SecurityPolicyMapper securityPolicyMapper;
    private final SecurityPolicyVersionMapper securityPolicyVersionMapper;
    private final SecurityBindingMapper securityBindingMapper;
    private final SecurityAuditEventMapper securityAuditEventMapper;
    private final SecurityIncidentMapper securityIncidentMapper;
    private final SecurityRulePackMapper securityRulePackMapper;
    private final SecurityPolicyConverter securityPolicyConverter;
    private final SecurityAuditEventService securityAuditEventService;
    private final EntityValidator entityValidator;
    private final AgentService agentService;
    private final WorkflowTemplateService workflowTemplateService;
    private final WorkspaceService workspaceService;

    @Override
    public SecurityOverviewVO getOverview() {
        var overview = new SecurityOverviewVO();
        overview.setTotalPolicies(countPolicies(null, null, null));
        overview.setActivePolicies(countPolicies(null, SecurityComplianceConstant.STATUS_ACTIVE, null));
        overview.setDraftPolicies(countPolicies(null, SecurityComplianceConstant.STATUS_DRAFT, null));
        overview.setCriticalPolicies(countPolicies(null, null, SecurityComplianceConstant.RISK_LEVEL_CRITICAL)
                + countPolicies(null, null, SecurityComplianceConstant.RISK_LEVEL_HIGH));
        overview.setBlockedEvents24h(countAuditEventsSince(SecurityComplianceConstant.AUDIT_STATUS_BLOCKED, LocalDateTime.now().minusHours(24)));
        overview.setWarningEvents24h(countAuditEventsSince(SecurityComplianceConstant.AUDIT_STATUS_WARNING, LocalDateTime.now().minusHours(24)));
        overview.setOpenIncidents(countOpenIncidents());
        overview.setActiveRulePacks(countActiveRulePacks());
        overview.setLatestPublishedAt(fetchLatestPublishedAt());
        overview.setDomainStats(buildDomainStats());
        overview.setRecentAuditEvents(securityAuditEventService.listRecent(6));
        overview.setRecentIncidents(listRecentIncidents(6));
        return overview;
    }

    @Override
    public SecurityTargetOptionsVO listTargetOptions(String scope) {
        if (StringUtils.hasText(scope) && !SecurityComplianceConstant.POLICY_SCOPES.contains(scope)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的生效范围");
        }
        var options = new SecurityTargetOptionsVO();
        options.setTenant(shouldIncludeScope(scope, SecurityComplianceConstant.SCOPE_TENANT) ? buildTenantOptions() : List.of());
        options.setAgent(shouldIncludeScope(scope, SecurityComplianceConstant.SCOPE_AGENT) ? buildAgentOptions() : List.of());
        options.setWorkflow(shouldIncludeScope(scope, SecurityComplianceConstant.SCOPE_WORKFLOW) ? buildWorkflowOptions() : List.of());
        options.setProject(shouldIncludeScope(scope, SecurityComplianceConstant.SCOPE_PROJECT) ? buildProjectOptions() : List.of());
        return options;
    }

    @Override
    public PageResult<SecurityPolicyVO> pagePolicies(SecurityPolicyQueryRequest request) {
        var page = new Page<SecurityPolicy>(request.getPage(), request.getSize());
        var wrapper = buildPolicyQueryWrapper(request);
        var result = securityPolicyMapper.selectPage(page, wrapper);
        return new PageResult<>(
                securityPolicyConverter.toVOList(result.getRecords()),
                result.getTotal(),
                result.getCurrent(),
                result.getSize()
        );
    }

    @Override
    public SecurityPolicyVO getPolicyById(String id) {
        return securityPolicyConverter.toVO(getPolicyEntity(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityPolicyVO createPolicy(SecurityPolicySaveRequest request, SecurityAuditContext auditContext) {
        validateSaveRequest(request);
        ensurePolicyCodeUnique(request.getPolicyCode(), null);

        var entity = securityPolicyConverter.fromRequest(request);
        entity.setTenantId(resolveTenantId());
        entity.setStatus(SecurityComplianceConstant.STATUS_DRAFT);
        entity.setVersion(0);
        entity.setIsBuiltin(false);
        entity.setTargetSelector(normalizeTargetSelector(request));
        securityPolicyMapper.insert(entity);

        securityAuditEventService.recordPolicyEvent(
                entity,
                SecurityComplianceConstant.EVENT_POLICY_CREATED,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                "创建安全策略",
                "已创建安全策略 " + entity.getPolicyName(),
                buildPolicyMetadata(entity),
                auditContext
        );
        return securityPolicyConverter.toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityPolicyVO updatePolicy(String id, SecurityPolicySaveRequest request, SecurityAuditContext auditContext) {
        validateSaveRequest(request);
        var entity = getPolicyEntity(id);
        ensurePolicyCodeUnique(request.getPolicyCode(), id);

        entity.setPolicyCode(request.getPolicyCode());
        entity.setPolicyName(request.getPolicyName());
        entity.setDomainCode(request.getDomainCode());
        entity.setPolicyType(request.getPolicyType());
        entity.setPolicyScope(request.getPolicyScope());
        entity.setEnforcementMode(request.getEnforcementMode());
        entity.setRiskLevel(request.getRiskLevel());
        entity.setControlPoints(request.getControlPoints());
        entity.setTags(request.getTags());
        entity.setTargetSelector(normalizeTargetSelector(request));
        entity.setPolicyConfig(request.getPolicyConfig());
        entity.setDescription(request.getDescription());
        if (SecurityComplianceConstant.STATUS_ACTIVE.equals(entity.getStatus())
                || SecurityComplianceConstant.STATUS_INACTIVE.equals(entity.getStatus())) {
            entity.setStatus(SecurityComplianceConstant.STATUS_DRAFT);
        }
        securityPolicyMapper.updateById(entity);

        securityAuditEventService.recordPolicyEvent(
                entity,
                SecurityComplianceConstant.EVENT_POLICY_UPDATED,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                "更新安全策略",
                "已更新安全策略 " + entity.getPolicyName(),
                buildPolicyMetadata(entity),
                auditContext
        );
        return securityPolicyConverter.toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityPolicyVO publishPolicy(String id, SecurityAuditContext auditContext) {
        var entity = getPolicyEntity(id);
        entity.setStatus(SecurityComplianceConstant.STATUS_ACTIVE);
        entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
        entity.setLastPublishedAt(LocalDateTime.now());
        securityPolicyMapper.updateById(entity);
        createVersionSnapshot(entity, "发布策略");

        securityAuditEventService.recordPolicyEvent(
                entity,
                SecurityComplianceConstant.EVENT_POLICY_PUBLISHED,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                "发布安全策略",
                "已发布安全策略 " + entity.getPolicyName() + "，版本 " + entity.getVersion(),
                buildPolicyMetadata(entity),
                auditContext
        );
        return securityPolicyConverter.toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityPolicyVO togglePolicy(String id, SecurityPolicyToggleRequest request, SecurityAuditContext auditContext) {
        var entity = getPolicyEntity(id);
        validateToggleStatus(request.getStatus());
        if (SecurityComplianceConstant.STATUS_DRAFT.equals(entity.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "草稿策略请先发布后再启停");
        }
        entity.setStatus(request.getStatus());
        securityPolicyMapper.updateById(entity);

        securityAuditEventService.recordPolicyEvent(
                entity,
                SecurityComplianceConstant.EVENT_POLICY_TOGGLED,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                "切换策略状态",
                "已将安全策略 " + entity.getPolicyName() + " 切换为 " + request.getStatus(),
                buildPolicyMetadata(entity),
                auditContext
        );
        return securityPolicyConverter.toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePolicy(String id, SecurityAuditContext auditContext) {
        var entity = getPolicyEntity(id);
        if (Boolean.TRUE.equals(entity.getIsBuiltin())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "内置安全策略不允许删除");
        }
        securityPolicyMapper.deleteById(id);
        securityBindingMapper.delete(new LambdaQueryWrapper<SecurityBinding>()
                .eq(SecurityBinding::getSourceType, SecurityComplianceConstant.SOURCE_TYPE_POLICY)
                .eq(SecurityBinding::getSourceId, id));
        securityAuditEventService.recordPolicyEvent(
                entity,
                SecurityComplianceConstant.EVENT_POLICY_DELETED,
                SecurityComplianceConstant.AUDIT_STATUS_WARNING,
                "删除安全策略",
                "已删除安全策略 " + entity.getPolicyName(),
                buildPolicyMetadata(entity),
                auditContext
        );
    }

    @Override
    public List<SecurityPolicyVersionVO> listPolicyVersions(String policyId) {
        getPolicyEntity(policyId);
        return securityPolicyVersionMapper.selectList(
                new LambdaQueryWrapper<SecurityPolicyVersion>()
                        .eq(SecurityPolicyVersion::getPolicyId, policyId)
                        .orderByDesc(SecurityPolicyVersion::getVersionNo)
                        .orderByDesc(SecurityPolicyVersion::getPublishedAt)
        ).stream().map(this::toPolicyVersionVO).toList();
    }

    @Override
    public List<SecurityBindingVO> listPolicyBindings(String policyId) {
        getPolicyEntity(policyId);
        return listBindings(SecurityComplianceConstant.SOURCE_TYPE_POLICY, policyId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SecurityBindingVO> savePolicyBindings(String policyId,
                                                      SecurityBindingSaveRequest request,
                                                      SecurityAuditContext auditContext) {
        var policy = getPolicyEntity(policyId);
        replaceBindings(SecurityComplianceConstant.SOURCE_TYPE_POLICY,
                policy.getId(),
                policy.getPolicyCode(),
                policy.getPolicyName(),
                policy.getDomainCode(),
                request);
        securityAuditEventService.recordPolicyEvent(
                policy,
                SecurityComplianceConstant.EVENT_POLICY_UPDATED,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                "更新策略绑定",
                "已更新安全策略绑定关系 " + policy.getPolicyName(),
                Map.of("bindingCount", listPolicyBindings(policyId).size()),
                auditContext
        );
        return listPolicyBindings(policyId);
    }

    private SecurityPolicy getPolicyEntity(String id) {
        return entityValidator.requireExists(securityPolicyMapper, id, ResultCode.SECURITY_POLICY_NOT_FOUND);
    }

    private LambdaQueryWrapper<SecurityPolicy> buildPolicyQueryWrapper(SecurityPolicyQueryRequest request) {
        var wrapper = new LambdaQueryWrapper<SecurityPolicy>();
        String tenantId = resolveTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityPolicy::getTenantId, tenantId);
        }
        if (StringUtils.hasText(request.getDomainCode())) {
            validateDomain(request.getDomainCode());
            wrapper.eq(SecurityPolicy::getDomainCode, request.getDomainCode());
        }
        if (StringUtils.hasText(request.getStatus())) {
            validateStatus(request.getStatus());
            wrapper.eq(SecurityPolicy::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getRiskLevel())) {
            validateRiskLevel(request.getRiskLevel());
            wrapper.eq(SecurityPolicy::getRiskLevel, request.getRiskLevel());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(q -> q.like(SecurityPolicy::getPolicyName, request.getKeyword())
                    .or()
                    .like(SecurityPolicy::getPolicyCode, request.getKeyword())
                    .or()
                    .like(SecurityPolicy::getPolicyType, request.getKeyword())
                    .or()
                    .like(SecurityPolicy::getDescription, request.getKeyword()));
        }
        wrapper.orderByDesc(SecurityPolicy::getUpdatedAt).orderByDesc(SecurityPolicy::getCreatedAt);
        return wrapper;
    }

    private void validateSaveRequest(SecurityPolicySaveRequest request) {
        validateDomain(request.getDomainCode());
        validateRiskLevel(request.getRiskLevel());
        if (!SecurityComplianceConstant.ENFORCEMENT_MODES.contains(request.getEnforcementMode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的执行模式");
        }
        if (!SecurityComplianceConstant.POLICY_SCOPES.contains(request.getPolicyScope())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的生效范围");
        }
    }

    private void validateDomain(String domainCode) {
        if (!SecurityComplianceConstant.SUPPORTED_DOMAINS.contains(domainCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的安全域");
        }
    }

    private void validateRiskLevel(String riskLevel) {
        if (!SecurityComplianceConstant.RISK_LEVELS.contains(riskLevel)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的风险等级");
        }
    }

    private void validateStatus(String status) {
        if (!SecurityComplianceConstant.POLICY_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的策略状态");
        }
    }

    private void validateToggleStatus(String status) {
        if (!SecurityComplianceConstant.STATUS_ACTIVE.equals(status)
                && !SecurityComplianceConstant.STATUS_INACTIVE.equals(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持切换为 active 或 inactive");
        }
    }

    private void ensurePolicyCodeUnique(String policyCode, String excludeId) {
        var wrapper = new LambdaQueryWrapper<SecurityPolicy>()
                .eq(SecurityPolicy::getTenantId, resolveTenantId())
                .eq(SecurityPolicy::getPolicyCode, policyCode);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(SecurityPolicy::getId, excludeId);
        }
        Long count = securityPolicyMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.SECURITY_POLICY_CODE_DUPLICATE);
        }
    }

    private long countPolicies(String domainCode, String status, String riskLevel) {
        var wrapper = new LambdaQueryWrapper<SecurityPolicy>();
        String tenantId = resolveTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityPolicy::getTenantId, tenantId);
        }
        if (StringUtils.hasText(domainCode)) {
            wrapper.eq(SecurityPolicy::getDomainCode, domainCode);
        }
        if (StringUtils.hasText(status)) {
            wrapper.eq(SecurityPolicy::getStatus, status);
        }
        if (StringUtils.hasText(riskLevel)) {
            wrapper.eq(SecurityPolicy::getRiskLevel, riskLevel);
        }
        Long count = securityPolicyMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private long countAuditEventsSince(String eventStatus, LocalDateTime since) {
        var wrapper = new LambdaQueryWrapper<SecurityAuditEvent>();
        String tenantId = resolveTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityAuditEvent::getTenantId, tenantId);
        }
        wrapper.eq(SecurityAuditEvent::getEventStatus, eventStatus).ge(SecurityAuditEvent::getOccurredAt, since);
        Long count = securityAuditEventMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private long countOpenIncidents() {
        var wrapper = new LambdaQueryWrapper<SecurityIncident>();
        String tenantId = resolveTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityIncident::getTenantId, tenantId);
        }
        wrapper.in(SecurityIncident::getStatus,
                SecurityComplianceConstant.INCIDENT_STATUS_NEW,
                SecurityComplianceConstant.INCIDENT_STATUS_ASSIGNED,
                SecurityComplianceConstant.INCIDENT_STATUS_INVESTIGATING,
                SecurityComplianceConstant.INCIDENT_STATUS_ESCALATED);
        Long count = securityIncidentMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private long countActiveRulePacks() {
        var wrapper = new LambdaQueryWrapper<SecurityRulePack>();
        String tenantId = resolveTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityRulePack::getTenantId, tenantId);
        }
        wrapper.eq(SecurityRulePack::getStatus, SecurityComplianceConstant.STATUS_ACTIVE);
        Long count = securityRulePackMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private LocalDateTime fetchLatestPublishedAt() {
        var wrapper = new LambdaQueryWrapper<SecurityPolicy>();
        String tenantId = resolveTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityPolicy::getTenantId, tenantId);
        }
        wrapper.isNotNull(SecurityPolicy::getLastPublishedAt)
                .orderByDesc(SecurityPolicy::getLastPublishedAt)
                .last("LIMIT 1");
        var policy = securityPolicyMapper.selectOne(wrapper);
        return policy != null ? policy.getLastPublishedAt() : null;
    }

    private List<SecurityDomainOverviewVO> buildDomainStats() {
        return SecurityComplianceConstant.SUPPORTED_DOMAINS.stream().map(domainCode -> {
            var item = new SecurityDomainOverviewVO();
            item.setDomainCode(domainCode);
            item.setTotalPolicies(countPolicies(domainCode, null, null));
            item.setActivePolicies(countPolicies(domainCode, SecurityComplianceConstant.STATUS_ACTIVE, null));
            item.setBlockedEvents24h(countBlockedEventsByDomain(domainCode));
            return item;
        }).toList();
    }

    private long countBlockedEventsByDomain(String domainCode) {
        var wrapper = new LambdaQueryWrapper<SecurityAuditEvent>();
        String tenantId = resolveTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityAuditEvent::getTenantId, tenantId);
        }
        wrapper.eq(SecurityAuditEvent::getDomainCode, domainCode)
                .eq(SecurityAuditEvent::getEventStatus, SecurityComplianceConstant.AUDIT_STATUS_BLOCKED)
                .ge(SecurityAuditEvent::getOccurredAt, LocalDateTime.now().minusHours(24));
        Long count = securityAuditEventMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private List<SecurityIncidentVO> listRecentIncidents(int limit) {
        return securityIncidentMapper.selectList(
                new LambdaQueryWrapper<SecurityIncident>()
                        .eq(StringUtils.hasText(resolveTenantId()), SecurityIncident::getTenantId, resolveTenantId())
                        .orderByDesc(SecurityIncident::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit))
        ).stream().map(this::toIncidentVO).toList();
    }

    private Map<String, Object> buildPolicyMetadata(SecurityPolicy entity) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("policyCode", entity.getPolicyCode());
        metadata.put("policyName", entity.getPolicyName());
        metadata.put("domainCode", entity.getDomainCode());
        metadata.put("policyScope", entity.getPolicyScope());
        metadata.put("status", entity.getStatus());
        metadata.put("version", entity.getVersion());
        metadata.put("enforcementMode", entity.getEnforcementMode());
        metadata.put(TARGET_COUNT_KEY, extractTargetCount(entity.getTargetSelector()));
        metadata.put("targetSummary", extractTargetSummary(entity.getTargetSelector()));
        return metadata;
    }

    private boolean shouldIncludeScope(String requestedScope, String actualScope) {
        return !StringUtils.hasText(requestedScope) || actualScope.equals(requestedScope);
    }

    private List<SecurityTargetOptionVO> buildTenantOptions() {
        return List.of(buildOption(
                SecurityComplianceConstant.SCOPE_TENANT,
                TARGET_CURRENT_VALUE,
                TARGET_CURRENT_VALUE,
                TARGET_READY_STATUS,
                Map.of(TARGET_TENANT_SCOPE_KEY, TARGET_CURRENT_VALUE)
        ));
    }

    private List<SecurityTargetOptionVO> buildAgentOptions() {
        return agentService.listAllAgents().stream().map(this::toAgentOption).toList();
    }

    private List<SecurityTargetOptionVO> buildWorkflowOptions() {
        return workflowTemplateService.listAll().stream().map(this::toWorkflowOption).toList();
    }

    private List<SecurityTargetOptionVO> buildProjectOptions() {
        return workspaceService.listAll().stream().map(this::toProjectOption).toList();
    }

    private SecurityTargetOptionVO toAgentOption(AgentVO agent) {
        return buildOption(
                SecurityComplianceConstant.SCOPE_AGENT,
                agent.getId(),
                agent.getName(),
                agent.getStatus(),
                Map.of(
                        "agentType", safeString(agent.getAgentType()),
                        "aiModel", safeString(agent.getAiModel()),
                        "configCompleted", Boolean.TRUE.equals(agent.getConfigCompleted()),
                        "agentTag", safeString(agent.getAgentTag())
                )
        );
    }

    private SecurityTargetOptionVO toWorkflowOption(WorkflowTemplateVO template) {
        return buildOption(
                SecurityComplianceConstant.SCOPE_WORKFLOW,
                template.getId(),
                template.getName(),
                Boolean.TRUE.equals(template.getIsBuiltin()) ? "builtin" : "custom",
                Map.of(
                        "category", safeString(template.getCategory()),
                        "builtin", Boolean.TRUE.equals(template.getIsBuiltin()),
                        "recommendedAgentSkills", template.getRecommendedAgentSkills() == null
                                ? List.of()
                                : template.getRecommendedAgentSkills()
                )
        );
    }

    private SecurityTargetOptionVO toProjectOption(WorkspaceVO workspace) {
        return buildOption(
                SecurityComplianceConstant.SCOPE_PROJECT,
                workspace.getId(),
                workspace.getName(),
                workspace.getWorkspaceStatus(),
                Map.of(
                        "sourceType", safeString(workspace.getSourceType()),
                        "gitPlatform", safeString(workspace.getGitPlatform()),
                        "workspaceScope", safeString(workspace.getWorkspaceScope())
                )
        );
    }

    private SecurityTargetOptionVO buildOption(String scope,
                                               String value,
                                               String label,
                                               String status,
                                               Map<String, Object> extra) {
        var option = new SecurityTargetOptionVO();
        option.setScope(scope);
        option.setValue(value);
        option.setLabel(label);
        option.setStatus(status);
        option.setExtra(extra);
        return option;
    }

    private Map<String, Object> normalizeTargetSelector(SecurityPolicySaveRequest request) {
        Map<String, Object> rawSelector = request.getTargetSelector() == null ? Map.of() : request.getTargetSelector();
        Map<String, Object> normalizedSelector = new LinkedHashMap<>(rawSelector);
        normalizedSelector.remove(TARGET_AGENT_IDS_KEY);
        normalizedSelector.remove(TARGET_WORKFLOW_TEMPLATE_IDS_KEY);
        normalizedSelector.remove(TARGET_WORKSPACE_IDS_KEY);
        normalizedSelector.remove(TARGET_RESOLVED_TARGETS_KEY);
        normalizedSelector.remove(TARGET_COUNT_KEY);
        normalizedSelector.put(TARGET_SCOPE_KEY, request.getPolicyScope());

        switch (request.getPolicyScope()) {
            case SecurityComplianceConstant.SCOPE_TENANT -> fillTenantSelector(normalizedSelector, rawSelector);
            case SecurityComplianceConstant.SCOPE_AGENT -> fillAgentSelector(normalizedSelector, rawSelector);
            case SecurityComplianceConstant.SCOPE_WORKFLOW -> fillWorkflowSelector(normalizedSelector, rawSelector);
            case SecurityComplianceConstant.SCOPE_PROJECT -> fillProjectSelector(normalizedSelector, rawSelector);
            default -> throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的生效范围");
        }
        return normalizedSelector;
    }

    private void fillTenantSelector(Map<String, Object> normalizedSelector, Map<String, Object> rawSelector) {
        String tenantScope = rawSelector.get(TARGET_TENANT_SCOPE_KEY) instanceof String value
                && StringUtils.hasText(value) ? value.trim() : TARGET_CURRENT_VALUE;
        normalizedSelector.put(TARGET_TENANT_SCOPE_KEY, tenantScope);
        normalizedSelector.put(TARGET_RESOLVED_TARGETS_KEY, List.of(buildResolvedTarget(
                SecurityComplianceConstant.SCOPE_TENANT,
                TARGET_CURRENT_VALUE,
                TARGET_CURRENT_VALUE,
                TARGET_READY_STATUS,
                Map.of(TARGET_TENANT_SCOPE_KEY, tenantScope)
        )));
        normalizedSelector.put(TARGET_COUNT_KEY, 1);
    }

    private void fillAgentSelector(Map<String, Object> normalizedSelector, Map<String, Object> rawSelector) {
        List<String> agentIds = extractTargetIds(rawSelector.get(TARGET_AGENT_IDS_KEY), "请选择至少一个 Agent");
        List<Map<String, Object>> resolvedTargets = resolveTargets(
                agentIds,
                buildAgentOptions(),
                "存在无效的 Agent 目标，请刷新页面后重试"
        );
        normalizedSelector.put(TARGET_AGENT_IDS_KEY, agentIds);
        normalizedSelector.put(TARGET_RESOLVED_TARGETS_KEY, resolvedTargets);
        normalizedSelector.put(TARGET_COUNT_KEY, resolvedTargets.size());
    }

    private void fillWorkflowSelector(Map<String, Object> normalizedSelector, Map<String, Object> rawSelector) {
        List<String> workflowTemplateIds = extractTargetIds(rawSelector.get(TARGET_WORKFLOW_TEMPLATE_IDS_KEY), "请选择至少一个工作流模板");
        List<Map<String, Object>> resolvedTargets = resolveTargets(
                workflowTemplateIds,
                buildWorkflowOptions(),
                "存在无效的工作流模板目标，请刷新页面后重试"
        );
        normalizedSelector.put(TARGET_WORKFLOW_TEMPLATE_IDS_KEY, workflowTemplateIds);
        normalizedSelector.put(TARGET_RESOLVED_TARGETS_KEY, resolvedTargets);
        normalizedSelector.put(TARGET_COUNT_KEY, resolvedTargets.size());
    }

    private void fillProjectSelector(Map<String, Object> normalizedSelector, Map<String, Object> rawSelector) {
        List<String> workspaceIds = extractTargetIds(rawSelector.get(TARGET_WORKSPACE_IDS_KEY), "请选择至少一个项目");
        List<Map<String, Object>> resolvedTargets = resolveTargets(
                workspaceIds,
                buildProjectOptions(),
                "存在无效的项目目标，请刷新页面后重试"
        );
        normalizedSelector.put(TARGET_WORKSPACE_IDS_KEY, workspaceIds);
        normalizedSelector.put(TARGET_RESOLVED_TARGETS_KEY, resolvedTargets);
        normalizedSelector.put(TARGET_COUNT_KEY, resolvedTargets.size());
    }

    private List<String> extractTargetIds(Object value, String emptyMessage) {
        if (!(value instanceof List<?> rawList)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, emptyMessage);
        }
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (Object item : rawList) {
            if (item instanceof String id && StringUtils.hasText(id)) {
                uniqueIds.add(id.trim());
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, emptyMessage);
        }
        return List.copyOf(uniqueIds);
    }

    private List<Map<String, Object>> resolveTargets(List<String> ids,
                                                     List<SecurityTargetOptionVO> options,
                                                     String invalidMessage) {
        Map<String, SecurityTargetOptionVO> optionIndex = new LinkedHashMap<>();
        for (SecurityTargetOptionVO option : options) {
            optionIndex.put(option.getValue(), option);
        }
        List<Map<String, Object>> resolvedTargets = new ArrayList<>();
        for (String id : ids) {
            SecurityTargetOptionVO option = optionIndex.get(id);
            if (option == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, invalidMessage);
            }
            resolvedTargets.add(buildResolvedTarget(
                    option.getScope(),
                    option.getValue(),
                    option.getLabel(),
                    option.getStatus(),
                    option.getExtra()
            ));
        }
        return resolvedTargets;
    }

    private Map<String, Object> buildResolvedTarget(String scope,
                                                    String value,
                                                    String label,
                                                    String status,
                                                    Map<String, Object> extra) {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put(TARGET_SCOPE_KEY, scope);
        target.put("value", value);
        target.put("label", label);
        target.put("status", status);
        if (extra != null && !extra.isEmpty()) {
            target.put("extra", extra);
        }
        return target;
    }

    private int extractTargetCount(Map<String, Object> targetSelector) {
        if (targetSelector == null) {
            return 0;
        }
        Object count = targetSelector.get(TARGET_COUNT_KEY);
        if (count instanceof Number number) {
            return number.intValue();
        }
        return extractTargetSummary(targetSelector).size();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTargetSummary(Map<String, Object> targetSelector) {
        if (targetSelector == null) {
            return List.of();
        }
        Object resolvedTargets = targetSelector.get(TARGET_RESOLVED_TARGETS_KEY);
        if (!(resolvedTargets instanceof List<?> rawList)) {
            return List.of();
        }
        List<Map<String, Object>> summary = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof Map<?, ?> map) {
                summary.add(new LinkedHashMap<>((Map<String, Object>) map));
            }
        }
        return summary;
    }

    private void createVersionSnapshot(SecurityPolicy policy, String changeSummary) {
        var version = new SecurityPolicyVersion();
        version.setTenantId(policy.getTenantId());
        version.setPolicyId(policy.getId());
        version.setVersionNo(policy.getVersion());
        version.setSnapshotConfig(policy.getPolicyConfig());
        version.setSnapshotTargets(Map.of(
                "targetSelector", policy.getTargetSelector() == null ? Map.of() : policy.getTargetSelector(),
                "bindings", listPolicyBindings(policy.getId())
        ));
        version.setChangeSummary(changeSummary);
        version.setPublishedBy(SecurityUtil.getCurrentUserId());
        version.setPublishedAt(LocalDateTime.now());
        securityPolicyVersionMapper.insert(version);
    }

    private List<SecurityBindingVO> listBindings(String sourceType, String sourceId) {
        return securityBindingMapper.selectList(
                new LambdaQueryWrapper<SecurityBinding>()
                        .eq(SecurityBinding::getTenantId, resolveTenantId())
                        .eq(SecurityBinding::getSourceType, sourceType)
                        .eq(SecurityBinding::getSourceId, sourceId)
                        .orderByAsc(SecurityBinding::getPriority)
                        .orderByDesc(SecurityBinding::getCreatedAt)
        ).stream().map(this::toBindingVO).toList();
    }

    private void replaceBindings(String sourceType,
                                 String sourceId,
                                 String sourceCode,
                                 String sourceName,
                                 String domainCode,
                                 SecurityBindingSaveRequest request) {
        securityBindingMapper.delete(new LambdaQueryWrapper<SecurityBinding>()
                .eq(SecurityBinding::getTenantId, resolveTenantId())
                .eq(SecurityBinding::getSourceType, sourceType)
                .eq(SecurityBinding::getSourceId, sourceId));
        if (request == null || request.getBindings() == null || request.getBindings().isEmpty()) {
            return;
        }
        for (SecurityBindingSaveRequest.Item item : request.getBindings()) {
            if (item == null || !StringUtils.hasText(item.getBindingType())) {
                continue;
            }
            var entity = new SecurityBinding();
            entity.setTenantId(resolveTenantId());
            entity.setSourceType(sourceType);
            entity.setSourceId(sourceId);
            entity.setSourceCode(sourceCode);
            entity.setSourceName(sourceName);
            entity.setBindingType(item.getBindingType().trim());
            entity.setTargetId(StringUtils.hasText(item.getTargetId()) ? item.getTargetId().trim() : null);
            entity.setTargetName(resolveBindingTargetName(item.getBindingType(), item.getTargetId(), item.getTargetName()));
            entity.setDomainCode(domainCode);
            entity.setStatus(SecurityComplianceConstant.STATUS_ACTIVE);
            entity.setPriority(item.getPriority() == null ? 100 : item.getPriority());
            entity.setEffectiveFrom(item.getEffectiveFrom());
            entity.setEffectiveTo(item.getEffectiveTo());
            entity.setBindingConfig(item.getBindingConfig());
            securityBindingMapper.insert(entity);
        }
    }

    private String resolveBindingTargetName(String bindingType, String targetId, String fallbackName) {
        if (StringUtils.hasText(fallbackName)) {
            return fallbackName.trim();
        }
        if (SecurityComplianceConstant.SCOPE_TENANT.equals(bindingType)) {
            return TARGET_CURRENT_VALUE;
        }
        if (SecurityComplianceConstant.SCOPE_AGENT.equals(bindingType)) {
            return buildAgentOptions().stream()
                    .filter(item -> item.getValue().equals(targetId))
                    .map(SecurityTargetOptionVO::getLabel)
                    .findFirst()
                    .orElse(targetId);
        }
        if (SecurityComplianceConstant.SCOPE_WORKFLOW.equals(bindingType)) {
            return buildWorkflowOptions().stream()
                    .filter(item -> item.getValue().equals(targetId))
                    .map(SecurityTargetOptionVO::getLabel)
                    .findFirst()
                    .orElse(targetId);
        }
        if (SecurityComplianceConstant.SCOPE_PROJECT.equals(bindingType)) {
            return buildProjectOptions().stream()
                    .filter(item -> item.getValue().equals(targetId))
                    .map(SecurityTargetOptionVO::getLabel)
                    .findFirst()
                    .orElse(targetId);
        }
        return targetId;
    }

    private SecurityPolicyVersionVO toPolicyVersionVO(SecurityPolicyVersion entity) {
        var vo = new SecurityPolicyVersionVO();
        vo.setId(entity.getId());
        vo.setPolicyId(entity.getPolicyId());
        vo.setVersionNo(entity.getVersionNo());
        vo.setSnapshotConfig(entity.getSnapshotConfig());
        vo.setSnapshotTargets(entity.getSnapshotTargets());
        vo.setChangeSummary(entity.getChangeSummary());
        vo.setPublishedBy(entity.getPublishedBy());
        vo.setPublishedAt(entity.getPublishedAt());
        return vo;
    }

    private SecurityBindingVO toBindingVO(SecurityBinding entity) {
        var vo = new SecurityBindingVO();
        vo.setId(entity.getId());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceId(entity.getSourceId());
        vo.setSourceCode(entity.getSourceCode());
        vo.setSourceName(entity.getSourceName());
        vo.setBindingType(entity.getBindingType());
        vo.setTargetId(entity.getTargetId());
        vo.setTargetName(entity.getTargetName());
        vo.setDomainCode(entity.getDomainCode());
        vo.setStatus(entity.getStatus());
        vo.setPriority(entity.getPriority());
        vo.setEffectiveFrom(entity.getEffectiveFrom());
        vo.setEffectiveTo(entity.getEffectiveTo());
        vo.setBindingConfig(entity.getBindingConfig());
        return vo;
    }

    private SecurityIncidentVO toIncidentVO(SecurityIncident entity) {
        var vo = new SecurityIncidentVO();
        vo.setId(entity.getId());
        vo.setTenantId(entity.getTenantId());
        vo.setIncidentNo(entity.getIncidentNo());
        vo.setTraceId(entity.getTraceId());
        vo.setDomainCode(entity.getDomainCode());
        vo.setRiskLevel(entity.getRiskLevel());
        vo.setStatus(entity.getStatus());
        vo.setSourceType(entity.getSourceType());
        vo.setSourceId(entity.getSourceId());
        vo.setSourceName(entity.getSourceName());
        vo.setPolicyId(entity.getPolicyId());
        vo.setPolicyCode(entity.getPolicyCode());
        vo.setDecision(entity.getDecision());
        vo.setEventTitle(entity.getEventTitle());
        vo.setEventDetail(entity.getEventDetail());
        vo.setAssignedTo(entity.getAssignedTo());
        vo.setAssignedName(entity.getAssignedName());
        vo.setResolvedAt(entity.getResolvedAt());
        vo.setResolutionSummary(entity.getResolutionSummary());
        vo.setPayload(entity.getPayload());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private String resolveTenantId() {
        return SecurityUtil.getCurrentTenantId();
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }
}
