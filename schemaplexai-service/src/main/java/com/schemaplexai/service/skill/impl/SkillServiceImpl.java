package com.schemaplexai.service.skill.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.SkillStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
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
        var tenantId = requireTenant();
        skillValidator.validateNameUnique(request.getName());

        var skill = skillConverter.fromCreateRequest(request);
        skill.setTenantId(tenantId);
        if (SkillStatusEnum.INACTIVE.getCode().equals(request.getInitialStatus())) {
            skill.setStatus(SkillStatusEnum.INACTIVE.getCode());
        }
        skillMapper.insert(skill);
        log.info("创建技能成功: skillId={}, name={}", skill.getId(), skill.getName());

        return skillConverter.toVO(skill);
    }

    @Override
    public PageResult<SkillVO> page(SkillQueryRequest request) {
        var page = new Page<Skill>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<Skill>();

        var tenantId = requireTenant();
        wrapper.and(w -> w.isNull(Skill::getTenantId).or().eq(Skill::getTenantId, tenantId));

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
        var skill = requireAccessible(id, false);
        return skillConverter.toVO(skill);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SkillVO update(String id, SkillUpdateRequest request) {
        var skill = requireAccessible(id, true);

        if (StringUtils.hasText(request.getDisplayName())) {
            skill.setDisplayName(request.getDisplayName());
        }
        if (request.getDescription() != null) {
            skill.setDescription(request.getDescription());
        }
        if (request.getSkillPrompt() != null) {
            skill.setSkillPrompt(request.getSkillPrompt());
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
        if (request.getResources() != null) {
            skill.setResources(request.getResources());
        }
        if (request.getExposureConfig() != null) {
            skill.setExposureConfig(request.getExposureConfig());
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
        requireAccessible(id, true);
        skillMapper.deleteById(id);
        log.info("删除技能成功: skillId={}", id);
    }
    private Skill requireAccessible(String id, boolean write) {
        var tenantId = requireTenant();
        var skill = entityValidator.requireExists(skillMapper, id, ResultCode.SKILL_NOT_FOUND);
        if (skill.getTenantId() == null && write) {
            throw new BusinessException(ResultCode.FORBIDDEN, "系统技能不可修改");
        }
        if (skill.getTenantId() != null && !tenantId.equals(skill.getTenantId())) {
            throw new BusinessException(ResultCode.SKILL_NOT_FOUND);
        }
        return skill;
    }

    private String requireTenant() {
        var tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return tenantId;
    }
}
