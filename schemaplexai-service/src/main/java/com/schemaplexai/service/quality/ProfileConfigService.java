package com.schemaplexai.service.quality;

import com.schemaplexai.model.entity.QualityProfile;

import java.util.List;

/**
 * 质量档案配置服务
 */
public interface ProfileConfigService {

    List<QualityProfile> list(String issueType);

    QualityProfile getById(String id);

    QualityProfile create(QualityProfile profile);

    QualityProfile update(String id, QualityProfile profile);

    void delete(String id);
}
