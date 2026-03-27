package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.QualityDimensionMapper;
import com.schemaplexai.model.entity.QualityDimension;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.DimensionConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 质量维度配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DimensionConfigServiceImpl implements DimensionConfigService {

    private final QualityDimensionMapper qualityDimensionMapper;
    private final EntityValidator entityValidator;

    @Override
    public List<QualityDimension> list(String issueType) {
        LambdaQueryWrapper<QualityDimension> wrapper = new LambdaQueryWrapper<QualityDimension>()
                .orderByAsc(QualityDimension::getSortOrder)
                .orderByAsc(QualityDimension::getCreatedAt);
        if (StringUtils.hasText(issueType)) {
            wrapper.eq(QualityDimension::getIssueType, issueType);
        }
        return qualityDimensionMapper.selectList(wrapper);
    }

    @Override
    public QualityDimension getById(String id) {
        return entityValidator.requireExists(qualityDimensionMapper, id, ResultCode.CONFIG_NOT_FOUND);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityDimension create(QualityDimension dimension) {
        if (dimension == null || !StringUtils.hasText(dimension.getCode())
                || !StringUtils.hasText(dimension.getName()) || !StringUtils.hasText(dimension.getIssueType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "质量维度参数不完整");
        }
        if (existsByCode(dimension.getCode(), null)) {
            throw new BusinessException(ResultCode.FAIL, "质量维度编码已存在: " + dimension.getCode());
        }
        if (!StringUtils.hasText(dimension.getStatus())) {
            dimension.setStatus(CommonConstant.STATUS_ACTIVE);
        }
        if (dimension.getSortOrder() == null) {
            dimension.setSortOrder(0);
        }
        if (dimension.getIsBuiltin() == null) {
            dimension.setIsBuiltin(false);
        }
        qualityDimensionMapper.insert(dimension);
        log.info("创建质量维度: id={}, code={}", dimension.getId(), dimension.getCode());
        return qualityDimensionMapper.selectById(dimension.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityDimension update(String id, QualityDimension dimension) {
        QualityDimension existing = entityValidator.requireExists(qualityDimensionMapper, id, ResultCode.CONFIG_NOT_FOUND);
        if (StringUtils.hasText(dimension.getCode()) && !dimension.getCode().equals(existing.getCode())
                && existsByCode(dimension.getCode(), id)) {
            throw new BusinessException(ResultCode.FAIL, "质量维度编码已存在: " + dimension.getCode());
        }

        QualityDimension patch = new QualityDimension();
        patch.setId(id);
        if (StringUtils.hasText(dimension.getCode())) patch.setCode(dimension.getCode());
        if (StringUtils.hasText(dimension.getName())) patch.setName(dimension.getName());
        if (StringUtils.hasText(dimension.getIssueType())) patch.setIssueType(dimension.getIssueType());
        if (dimension.getDescription() != null) patch.setDescription(dimension.getDescription());
        if (dimension.getSortOrder() != null) patch.setSortOrder(dimension.getSortOrder());
        if (StringUtils.hasText(dimension.getStatus())) patch.setStatus(dimension.getStatus());
        if (dimension.getIsBuiltin() != null) patch.setIsBuiltin(dimension.getIsBuiltin());
        if (dimension.getExtConfig() != null) patch.setExtConfig(dimension.getExtConfig());

        qualityDimensionMapper.updateById(patch);
        log.info("更新质量维度: id={}", id);
        return qualityDimensionMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(qualityDimensionMapper, id, ResultCode.CONFIG_NOT_FOUND);
        qualityDimensionMapper.deleteById(id);
        log.info("删除质量维度: id={}", id);
    }

    private boolean existsByCode(String code, String excludeId) {
        LambdaQueryWrapper<QualityDimension> wrapper = new LambdaQueryWrapper<QualityDimension>()
                .eq(QualityDimension::getCode, code);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(QualityDimension::getId, excludeId);
        }
        return qualityDimensionMapper.selectCount(wrapper) > 0;
    }
}
