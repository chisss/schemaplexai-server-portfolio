package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.TriggerModeEnum;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.QualityRuleMapper;
import com.schemaplexai.model.entity.QualityRule;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.RuleConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 质量规则配置服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleConfigServiceImpl implements RuleConfigService {

    private final QualityRuleMapper qualityRuleMapper;
    private final EntityValidator entityValidator;

    @Override
    public List<QualityRule> list(String dimensionCode, String triggerMode) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<QualityRule>()
                .orderByAsc(QualityRule::getCreatedAt);
        if (StringUtils.hasText(dimensionCode)) {
            wrapper.eq(QualityRule::getDimensionCode, dimensionCode);
        }
        if (StringUtils.hasText(triggerMode)) {
            wrapper.eq(QualityRule::getTriggerMode, triggerMode);
        }
        return qualityRuleMapper.selectList(wrapper);
    }

    @Override
    public QualityRule getById(String id) {
        return entityValidator.requireExists(qualityRuleMapper, id, ResultCode.CONFIG_NOT_FOUND);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityRule create(QualityRule rule) {
        if (rule == null || !StringUtils.hasText(rule.getDimensionCode())
                || !StringUtils.hasText(rule.getCode()) || !StringUtils.hasText(rule.getName())
                || !StringUtils.hasText(rule.getRuleType())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "质量规则参数不完整");
        }
        if (existsByCode(rule.getCode(), null)) {
            throw new BusinessException(ResultCode.FAIL, "质量规则编码已存在: " + rule.getCode());
        }
        if (!StringUtils.hasText(rule.getTriggerMode())) {
            rule.setTriggerMode(TriggerModeEnum.MANUAL.getCode());
        }
        if (!StringUtils.hasText(rule.getSeverity())) {
            rule.setSeverity(DeviationSeverityEnum.WARNING.getCode());
        }
        if (!StringUtils.hasText(rule.getStatus())) {
            rule.setStatus(CommonConstant.STATUS_ACTIVE);
        }
        if (rule.getWeight() == null) {
            rule.setWeight(100);
        }
        if (rule.getIsBuiltin() == null) {
            rule.setIsBuiltin(false);
        }
        qualityRuleMapper.insert(rule);
        log.info("创建质量规则: id={}, code={}", rule.getId(), rule.getCode());
        return qualityRuleMapper.selectById(rule.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityRule update(String id, QualityRule rule) {
        QualityRule existing = entityValidator.requireExists(qualityRuleMapper, id, ResultCode.CONFIG_NOT_FOUND);
        if (StringUtils.hasText(rule.getCode()) && !rule.getCode().equals(existing.getCode())
                && existsByCode(rule.getCode(), id)) {
            throw new BusinessException(ResultCode.FAIL, "质量规则编码已存在: " + rule.getCode());
        }

        QualityRule patch = new QualityRule();
        patch.setId(id);
        if (StringUtils.hasText(rule.getDimensionId())) patch.setDimensionId(rule.getDimensionId());
        if (StringUtils.hasText(rule.getDimensionCode())) patch.setDimensionCode(rule.getDimensionCode());
        if (StringUtils.hasText(rule.getCode())) patch.setCode(rule.getCode());
        if (StringUtils.hasText(rule.getName())) patch.setName(rule.getName());
        if (StringUtils.hasText(rule.getRuleType())) patch.setRuleType(rule.getRuleType());
        if (StringUtils.hasText(rule.getTriggerMode())) patch.setTriggerMode(rule.getTriggerMode());
        if (StringUtils.hasText(rule.getSeverity())) patch.setSeverity(rule.getSeverity());
        if (rule.getWeight() != null) patch.setWeight(rule.getWeight());
        if (rule.getConditionExpr() != null) patch.setConditionExpr(rule.getConditionExpr());
        if (rule.getRuleConfig() != null) patch.setRuleConfig(rule.getRuleConfig());
        if (StringUtils.hasText(rule.getStatus())) patch.setStatus(rule.getStatus());
        if (rule.getIsBuiltin() != null) patch.setIsBuiltin(rule.getIsBuiltin());

        qualityRuleMapper.updateById(patch);
        log.info("更新质量规则: id={}", id);
        return qualityRuleMapper.selectById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(qualityRuleMapper, id, ResultCode.CONFIG_NOT_FOUND);
        qualityRuleMapper.deleteById(id);
        log.info("删除质量规则: id={}", id);
    }

    private boolean existsByCode(String code, String excludeId) {
        LambdaQueryWrapper<QualityRule> wrapper = new LambdaQueryWrapper<QualityRule>()
                .eq(QualityRule::getCode, code);
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(QualityRule::getId, excludeId);
        }
        return qualityRuleMapper.selectCount(wrapper) > 0;
    }
}
