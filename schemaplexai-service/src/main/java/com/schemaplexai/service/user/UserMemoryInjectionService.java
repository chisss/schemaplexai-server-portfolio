package com.schemaplexai.service.user;

/**
 * 用户记忆 Prompt 注入服务
 */
public interface UserMemoryInjectionService {

    UserMemoryPromptPart buildPromptPart(String tenantId, String userId, String agentId,
                                         String projectId, String currentInput, boolean temporaryChat);
}
