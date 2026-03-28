package com.schemaplexai.service.skill.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.converter.SkillConverter;
import com.schemaplexai.model.dto.skill.SkillCreateRequest;
import com.schemaplexai.model.dto.skill.SkillQueryRequest;
import com.schemaplexai.model.dto.skill.SkillUpdateRequest;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.vo.skill.SkillVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.skill.SkillService;
import com.schemaplexai.service.skill.validator.SkillValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 技能服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillServiceImpl implements SkillService {

    private final SkillMapper skillMapper;
    private final SkillConverter skillConverter;
    private final SkillValidator skillValidator;
    private final EntityValidator entityValidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVO create(SkillCreateRequest request) {
        // 校验名称唯一性
        skillValidator.validateNameUnique(request.getName());

        var skill = skillConverter.fromCreateRequest(request);
        skillMapper.insert(skill);
        log.info("创建技能成功: skillId={}, name={}", skill.getId(), skill.getName());

        return skillConverter.toVO(skill);
    }

    @Override
    public PageResult<SkillVO> page(SkillQueryRequest request) {
        var page = new Page<Skill>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<Skill>();

        // 获取当前租户ID，用于同时返回内置技能(tenant_id=NULL)和租户级技能
        var tenantId = SecurityUtil.getCurrentTenantId();

        // 租户过滤：返回内置技能(tenant_id IS NULL)或当前租户创建的技能
        if (tenantId != null) {
            wrapper.and(w -> w.isNull(Skill::getTenantId).or().eq(Skill::getTenantId, tenantId));
        } else {
            // 未登录场景返回所有技能
        }

        if (StringUtils.hasText(request.getCategory())) {
            wrapper.eq(Skill::getCategory, request.getCategory());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(Skill::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(Skill::getName, request.getKeyword())
                    .or().like(Skill::getDisplayName, request.getKeyword())
                    .or().like(Skill::getDescription, request.getKeyword()));
        }
        wrapper.orderByDesc(Skill::getCreatedAt);

        var result = skillMapper.selectPage(page, wrapper);
        var voList = skillConverter.toVOList(result.getRecords());
        // 设置内置标识
        for (var vo : voList) {
            vo.setBuiltIn(vo.getTenantId() == null);
        }
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public SkillVO getById(String id) {
        var skill = entityValidator.requireExists(skillMapper, id, ResultCode.SKILL_NOT_FOUND);
        return skillConverter.toVO(skill);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVO update(String id, SkillUpdateRequest request) {
        var skill = entityValidator.requireExists(skillMapper, id, ResultCode.SKILL_NOT_FOUND);

        if (StringUtils.hasText(request.getDisplayName())) {
            skill.setDisplayName(request.getDisplayName());
        }
        if (request.getDescription() != null) {
            skill.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getVersion())) {
            skill.setVersion(request.getVersion());
        }
        if (StringUtils.hasText(request.getCategory())) {
            skill.setCategory(request.getCategory());
        }
        if (request.getParameters() != null) {
            skill.setParameters(request.getParameters());
        }
        if (request.getImplementation() != null) {
            skill.setImplementation(request.getImplementation());
        }
        if (StringUtils.hasText(request.getStatus())) {
            skill.setStatus(request.getStatus());
        }

        skillMapper.updateById(skill);
        log.info("更新技能成功: skillId={}", id);

        return skillConverter.toVO(skill);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(skillMapper, id, ResultCode.SKILL_NOT_FOUND);
        skillMapper.deleteById(id);
        log.info("删除技能成功: skillId={}", id);
    }
}
