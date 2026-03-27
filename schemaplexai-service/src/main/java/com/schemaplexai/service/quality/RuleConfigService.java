package com.schemaplexai.service.quality;

import com.schemaplexai.model.entity.QualityRule;

import java.util.List;

/**
 * 质量规则配置服务
 */
public interface RuleConfigService {

    List<QualityRule> list(String dimensionCode, String triggerMode);

    QualityRule getById(String id);

    QualityRule create(QualityRule rule);

    QualityRule update(String id, QualityRule rule);

    void delete(String id);
}
