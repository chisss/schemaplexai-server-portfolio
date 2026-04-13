package com.schemaplexai.service.quality;

import com.schemaplexai.model.entity.CrossReview;

import java.util.List;

/**
 * 质量交叉审查执行服务
 */
public interface QualityCrossReviewExecutionService {

    CrossReview createAndExecute(String specId,
                                 String taskId,
                                 String profileId,
                                 String issueType,
                                 List<String> modelIds,
                                 String sourceType,
                                 String sourceAgentId,
                                 String targetContent,
                                 String tenantId);
}
