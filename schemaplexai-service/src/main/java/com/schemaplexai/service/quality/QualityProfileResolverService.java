package com.schemaplexai.service.quality;

import com.schemaplexai.model.entity.QualityProfile;

import java.util.List;

/**
 * 质量配置组运行时解析服务
 */
public interface QualityProfileResolverService {

    QualityProfile resolveProfile(String specId, String workflowTemplateId, String agentId, String issueType);

    List<String> listModelIds(String profileId);
}
