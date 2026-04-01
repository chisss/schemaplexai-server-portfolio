package com.schemaplexai.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecurityBindingMapper;
import com.schemaplexai.dao.mapper.SecurityRuleItemMapper;
import com.schemaplexai.dao.mapper.SecurityRulePackMapper;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityBindingSaveRequest;
import com.schemaplexai.model.dto.security.SecurityRuleItemSaveRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackQueryRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackSaveRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackToggleRequest;
import com.schemaplexai.model.entity.SecurityBinding;
import com.schemaplexai.model.entity.SecurityRuleItem;
import com.schemaplexai.model.entity.SecurityRulePack;
import com.schemaplexai.model.vo.security.SecurityBindingVO;
import com.schemaplexai.model.vo.security.SecurityRuleItemVO;
import com.schemaplexai.model.vo.security.SecurityRulePackVO;
import com.schemaplexai.service.security.SecurityAuditEventService;
import com.schemaplexai.service.security.SecurityRulePackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全规则包服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityRulePackServiceImpl implements SecurityRulePackService {

    private final SecurityRulePackMapper securityRulePackMapper;
    private final SecurityRuleItemMapper securityRuleItemMapper;
    private final SecurityBindingMapper securityBindingMapper;
    private final SecurityAuditEventService securityAuditEventService;

    @Override
    public PageResult<SecurityRulePackVO> page(SecurityRulePackQueryRequest request) {
        var page = new Page<SecurityRulePack>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<SecurityRulePack>();
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId)) {
            wrapper.eq(SecurityRulePack::getTenantId, tenantId);
        }
        if (StringUtils.hasText(request.getIndustryCode())) {
            wrapper.eq(SecurityRulePack::getIndustryCode, request.getIndustryCode());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(SecurityRulePack::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(q -> q.like(SecurityRulePack::getPackCode, request.getKeyword())
                    .or().like(SecurityRulePack::getPackName, request.getKeyword())
                    .or().like(SecurityRulePack::getDescription, request.getKeyword()));
        }
        wrapper.orderByDesc(SecurityRulePack::getUpdatedAt).orderByDesc(SecurityRulePack::getCreatedAt);
        var result = securityRulePackMapper.selectPage(page, wrapper);
        return new PageResult<>(result.getRecords().stream().map(this::toVO).toList(),
                result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public SecurityRulePackVO getById(String id) {
        return toVO(requirePack(id), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityRulePackVO create(SecurityRulePackSaveRequest request, SecurityAuditContext auditContext) {
        validateRequest(request);
        ensurePackCodeUnique(request.getPackCode(), null);
        var entity = new SecurityRulePack();
        entity.setTenantId(resolveTenantId());
        entity.setPackCode(request.getPackCode());
        entity.setPackName(request.getPackName());
        entity.setIndustryCode(request.getIndustryCode());
        entity.setStatus(SecurityComplianceConstant.STATUS_DRAFT);
        entity.setPackVersion(0);
        entity.setDefaultAction(request.getDefaultAction());
        entity.setIsBuiltin(false);
        entity.setDescription(request.getDescription());
        entity.setPackConfig(request.getPackConfig());
        securityRulePackMapper.insert(entity);
        replaceItems(entity.getId(), request.getItems());
        recordPackAudit(entity, SecurityComplianceConstant.EVENT_POLICY_CREATED, "创建规则包", auditContext);
        return toVO(entity, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityRulePackVO update(String id, SecurityRulePackSaveRequest request, SecurityAuditContext auditContext) {
        validateRequest(request);
        var entity = requirePack(id);
        ensurePackCodeUnique(request.getPackCode(), id);
        entity.setPackCode(request.getPackCode());
        entity.setPackName(request.getPackName());
        entity.setIndustryCode(request.getIndustryCode());
        entity.setDefaultAction(request.getDefaultAction());
        entity.setDescription(request.getDescription());
        entity.setPackConfig(request.getPackConfig());
        if (SecurityComplianceConstant.STATUS_ACTIVE.equals(entity.getStatus())
                || SecurityComplianceConstant.STATUS_INACTIVE.equals(entity.getStatus())) {
            entity.setStatus(SecurityComplianceConstant.STATUS_DRAFT);
        }
        securityRulePackMapper.updateById(entity);
        replaceItems(entity.getId(), request.getItems());
        recordPackAudit(entity, SecurityComplianceConstant.EVENT_POLICY_UPDATED, "更新规则包", auditContext);
        return toVO(entity, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SecurityRulePackVO toggle(String id, SecurityRulePackToggleRequest request, SecurityAuditContext auditContext) {
        var entity = requirePack(id);
        if (!SecurityComplianceConstant.POLICY_STATUSES.contains(request.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的规则包状态");
        }
        entity.setStatus(request.getStatus());
        if (SecurityComplianceConstant.STATUS_ACTIVE.equals(request.getStatus())) {
            entity.setPackVersion(entity.getPackVersion() == null ? 1 : entity.getPackVersion() + 1);
        }
        securityRulePackMapper.updateById(entity);
        recordPackAudit(entity, SecurityComplianceConstant.EVENT_POLICY_TOGGLED, "切换规则包状态", auditContext);
        return toVO(entity, true);
    }

    @Override
    public List<SecurityRuleItemVO> listItems(String packId) {
        requirePack(packId);
        return securityRuleItemMapper.selectList(
                new LambdaQueryWrapper<SecurityRuleItem>()
                        .eq(SecurityRuleItem::getPackId, packId)
                        .orderByAsc(SecurityRuleItem::getSortOrder)
                        .orderByAsc(SecurityRuleItem::getCreatedAt)
        ).stream().map(this::toItemVO).toList();
    }

    @Override
    public List<SecurityBindingVO> listBindings(String packId) {
        requirePack(packId);
        return securityBindingMapper.selectList(
                new LambdaQueryWrapper<SecurityBinding>()
                        .eq(SecurityBinding::getTenantId, resolveTenantId())
                        .eq(SecurityBinding::getSourceType, SecurityComplianceConstant.SOURCE_TYPE_RULE_PACK)
                        .eq(SecurityBinding::getSourceId, packId)
                        .orderByAsc(SecurityBinding::getPriority)
                        .orderByDesc(SecurityBinding::getCreatedAt)
        ).stream().map(this::toBindingVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<SecurityBindingVO> saveBindings(String packId,
                                                SecurityBindingSaveRequest request,
                                                SecurityAuditContext auditContext) {
        var pack = requirePack(packId);
        securityBindingMapper.delete(new LambdaQueryWrapper<SecurityBinding>()
                .eq(SecurityBinding::getTenantId, resolveTenantId())
                .eq(SecurityBinding::getSourceType, SecurityComplianceConstant.SOURCE_TYPE_RULE_PACK)
                .eq(SecurityBinding::getSourceId, packId));
        if (request != null && request.getBindings() != null) {
            for (SecurityBindingSaveRequest.Item item : request.getBindings()) {
                var binding = new SecurityBinding();
                binding.setTenantId(resolveTenantId());
                binding.setSourceType(SecurityComplianceConstant.SOURCE_TYPE_RULE_PACK);
                binding.setSourceId(packId);
                binding.setSourceCode(pack.getPackCode());
                binding.setSourceName(pack.getPackName());
                binding.setBindingType(item.getBindingType());
                binding.setTargetId(item.getTargetId());
                binding.setTargetName(StringUtils.hasText(item.getTargetName()) ? item.getTargetName() : item.getTargetId());
                binding.setDomainCode(SecurityComplianceConstant.DOMAIN_INDUSTRY);
                binding.setStatus(SecurityComplianceConstant.STATUS_ACTIVE);
                binding.setPriority(item.getPriority() == null ? 100 : item.getPriority());
                binding.setEffectiveFrom(item.getEffectiveFrom());
                binding.setEffectiveTo(item.getEffectiveTo());
                binding.setBindingConfig(item.getBindingConfig());
                securityBindingMapper.insert(binding);
            }
        }
        recordPackAudit(pack, SecurityComplianceConstant.EVENT_POLICY_UPDATED, "更新规则包绑定", auditContext);
        return listBindings(packId);
    }

    private void validateRequest(SecurityRulePackSaveRequest request) {
        if (!SecurityComplianceConstant.CHECK_DECISIONS.contains(request.getDefaultAction())
                || SecurityComplianceConstant.DECISION_ALLOW.equals(request.getDefaultAction())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "默认动作仅支持 warn/block/pause");
        }
    }

    private void replaceItems(String packId, List<SecurityRuleItemSaveRequest> items) {
        securityRuleItemMapper.delete(new LambdaQueryWrapper<SecurityRuleItem>()
                .eq(SecurityRuleItem::getPackId, packId));
        if (items == null || items.isEmpty()) {
            return;
        }
        for (SecurityRuleItemSaveRequest item : items) {
            var entity = new SecurityRuleItem();
            entity.setTenantId(resolveTenantId());
            entity.setPackId(packId);
            entity.setItemCode(item.getItemCode());
            entity.setItemName(item.getItemName());
            entity.setRuleSource(item.getRuleSource());
            entity.setRuleClause(item.getRuleClause());
            entity.setRiskLevel(item.getRiskLevel());
            entity.setAction(item.getAction());
            entity.setMatchType(item.getMatchType());
            entity.setMatchContent(item.getMatchContent());
            entity.setSortOrder(item.getSortOrder() == null ? 100 : item.getSortOrder());
            entity.setEnabled(item.getEnabled() == null ? Boolean.TRUE : item.getEnabled());
            entity.setItemConfig(item.getItemConfig());
            securityRuleItemMapper.insert(entity);
        }
    }

    private void ensurePackCodeUnique(String packCode, String excludeId) {
        var wrapper = new LambdaQueryWrapper<SecurityRulePack>()
                .eq(SecurityRulePack::getTenantId, resolveTenantId())
                .eq(SecurityRulePack::getPackCode, packCode);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(SecurityRulePack::getId, excludeId);
        }
        Long count = securityRulePackMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.SECURITY_POLICY_CODE_DUPLICATE, "规则包编码已存在");
        }
    }

    private SecurityRulePack requirePack(String id) {
        var entity = securityRulePackMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException(ResultCode.SECURITY_RULE_PACK_NOT_FOUND);
        }
        return entity;
    }

    private void recordPackAudit(SecurityRulePack pack, String eventType, String title, SecurityAuditContext auditContext) {
        securityAuditEventService.recordEvent(
                pack.getTenantId(),
                null,
                eventType,
                SecurityComplianceConstant.AUDIT_SOURCE_RULE_PACK,
                SecurityComplianceConstant.AUDIT_STATUS_SUCCESS,
                SecurityComplianceConstant.RISK_LEVEL_MEDIUM,
                SecurityComplianceConstant.DOMAIN_INDUSTRY,
                null,
                null,
                SecurityComplianceConstant.RESOURCE_TYPE_RULE_PACK,
                pack.getId(),
                title,
                title + ": " + pack.getPackName(),
                Map.of("packCode", pack.getPackCode(), "status", pack.getStatus(), "version", pack.getPackVersion()),
                auditContext
        );
    }

    private SecurityRulePackVO toVO(SecurityRulePack entity) {
        return toVO(entity, false);
    }

    private SecurityRulePackVO toVO(SecurityRulePack entity, boolean includeChildren) {
        var vo = new SecurityRulePackVO();
        vo.setId(entity.getId());
        vo.setTenantId(entity.getTenantId());
        vo.setPackCode(entity.getPackCode());
        vo.setPackName(entity.getPackName());
        vo.setIndustryCode(entity.getIndustryCode());
        vo.setStatus(entity.getStatus());
        vo.setPackVersion(entity.getPackVersion());
        vo.setDefaultAction(entity.getDefaultAction());
        vo.setIsBuiltin(entity.getIsBuiltin());
        vo.setDescription(entity.getDescription());
        vo.setPackConfig(entity.getPackConfig());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        if (includeChildren) {
            vo.setItems(listItems(entity.getId()));
            vo.setBindings(listBindings(entity.getId()));
        }
        return vo;
    }

    private SecurityRuleItemVO toItemVO(SecurityRuleItem entity) {
        var vo = new SecurityRuleItemVO();
        vo.setId(entity.getId());
        vo.setPackId(entity.getPackId());
        vo.setItemCode(entity.getItemCode());
        vo.setItemName(entity.getItemName());
        vo.setRuleSource(entity.getRuleSource());
        vo.setRuleClause(entity.getRuleClause());
        vo.setRiskLevel(entity.getRiskLevel());
        vo.setAction(entity.getAction());
        vo.setMatchType(entity.getMatchType());
        vo.setMatchContent(entity.getMatchContent());
        vo.setSortOrder(entity.getSortOrder());
        vo.setEnabled(entity.getEnabled());
        vo.setItemConfig(entity.getItemConfig());
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

    private String resolveTenantId() {
        return SecurityUtil.getCurrentTenantId();
    }
}
