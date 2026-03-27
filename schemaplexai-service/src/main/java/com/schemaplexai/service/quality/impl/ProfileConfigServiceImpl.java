package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.QualityProfileMapper;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.ProfileConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 质量档案配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileConfigServiceImpl implements ProfileConfigService {

    private final QualityProfileMapper qualityProfileMapper;
    private final EntityValidator entityValidator;

    @Override
    public List<QualityProfile> list(String issueType) {
        LambdaQueryWrapper<QualityProfile> wrapper = new LambdaQueryWrapper<QualityProfile>()
                .orderByDesc(QualityProfile::getIsDefault)
                .orderByAsc(QualityProfile::getCreatedAt);
        if (StringUtils.hasText(issueType)) {
            wrapper.eq(QualityProfile::getIssueType, issueType);
        }
        return qualityProfileMapper.selectList(wrapper);
    }

    @Override
    public QualityProfile getById(String id) {
        return entityValidator.requireExists(qualityProfileMapper, id, ResultCode.CONFIG_NOT_FOUND);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityProfile create(QualityProfile profile) {
        if (profile == null || !StringUtils.hasText(profile.getCode())
                || !StringUtils.hasText(profile.getName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "质量档案参数不完整");
        }
        if (existsByCode(profile.getCode(), null)) {
            throw new BusinessException(ResultCode.FAIL, "质量档案编码已存在: " + profile.getCode());
        }
        if (!StringUtils.hasText(profile.getIssueType())) {
            profile.setIssueType("both");
        }
        if (!StringUtils.hasText(profile.getStatus())) {
            profile.setStatus(CommonConstant.STATUS_ACTIVE);
        }
        if (profile.getVersion() == null) {
            profile.setVersion(1);
        }
        if (profile.getIsDefault() == null) {
            profile.setIsDefault(false);
        }
        if (profile.getIsBuiltin() == null) {
            profile.setIsBuiltin(false);
        }
        qualityProfileMapper.insert(profile);
        log.info("创建质量档案: id={}, code={}", profile.getId(), profile.getCode());
        return qualityProfileMapper.selectById(profile.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityProfile update(String id, QualityProfile profile) {
        QualityProfile existing = entityValidator.requireExists(qualityProfileMapper, id, ResultCode.CONFIG_NOT_FOUND);
        if (StringUtils.hasText(profile.getCode()) && !profile.getCode().equals(existing.getCode())
                && existsByCode(profile.getCode(), id)) {
            throw new BusinessException(ResultCode.FAIL, "质量档案编码已存在: " + profile.getCode());
        }

        QualityProfile patch = new QualityProfile();
        patch.setId(id);
        if (StringUtils.hasText(profile.getCode())) patch.setCode(profile.getCode());
        if (StringUtils.hasText(profile.getName())) patch.setName(profile.getName());
        if (StringUtils.hasText(profile.getIssueType())) patch.setIssueType(profile.getIssueType());
        if (profile.getDescription() != null) patch.setDescription(profile.getDescription());
        if (profile.getTriggerModes() != null) patch.setTriggerModes(profile.getTriggerModes());
        if (profile.getEnabledDimensionCodes() != null) patch.setEnabledDimensionCodes(profile.getEnabledDimensionCodes());
        if (profile.getEnabledRuleCodes() != null) patch.setEnabledRuleCodes(profile.getEnabledRuleCodes());
        if (profile.getThresholdConfig() != null) patch.setThresholdConfig(profile.getThresholdConfig());
        if (StringUtils.hasText(profile.getStatus())) patch.setStatus(profile.getStatus());
        if (profile.getIsDefault() != null) patch.setIsDefault(profile.getIsDefault());
        if (profile.getIsBuiltin() != null) patch.setIsBuiltin(profile.getIsBuiltin());
        if (profile.getVersion() != null) patch.setVersion(profile.getVersion());
        if (profile.getPublishedAt() != null) patch.setPublishedAt(profile.getPublishedAt());

        qualityProfileMapper.updateById(patch);
        log.info("更新质量档案: id={}", id);
        return qualityProfileMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(qualityProfileMapper, id, ResultCode.CONFIG_NOT_FOUND);
        qualityProfileMapper.deleteById(id);
        log.info("删除质量档案: id={}", id);
    }

    private boolean existsByCode(String code, String excludeId) {
        LambdaQueryWrapper<QualityProfile> wrapper = new LambdaQueryWrapper<QualityProfile>()
                .eq(QualityProfile::getCode, code);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(QualityProfile::getId, excludeId);
        }
        return qualityProfileMapper.selectCount(wrapper) > 0;
    }
}
