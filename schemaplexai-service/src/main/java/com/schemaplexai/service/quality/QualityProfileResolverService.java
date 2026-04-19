package com.schemaplexai.service.quality;

import com.schemaplexai.model.entity.QualityProfile;

import java.util.List;

/**
 * 质量配置组运行时解析服务
 */
public interface QualityProfileResolverService {

    QualityProfile resolveProfile(String specId, String workflowTemplateId, String agentId, String issueType, String sourceType);

    /**
     * 根据 profileId 直接查询
     */
    default QualityProfile resolveProfileById(String profileId) {
        return null;
    }

    List<String> listModelIds(String profileId);
}
