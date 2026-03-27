package com.schemaplexai.service.quality;

import com.schemaplexai.model.entity.QualityDimension;

import java.util.List;

/**
 * 质量维度配置服务
 */
public interface DimensionConfigService {

    List<QualityDimension> list(String issueType);

    QualityDimension getById(String id);

    QualityDimension create(QualityDimension dimension);

    QualityDimension update(String id, QualityDimension dimension);

    void delete(String id);
}
