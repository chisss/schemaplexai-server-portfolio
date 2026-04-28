package com.schemaplexai.service.user;

import com.schemaplexai.model.dto.user.UserMemoryCreateRequest;
import com.schemaplexai.model.dto.user.UserMemoryQueryRequest;
import com.schemaplexai.model.dto.user.UserMemorySettingUpdateRequest;
import com.schemaplexai.model.dto.user.UserMemoryUpdateRequest;
import com.schemaplexai.model.entity.UserMemory;
import com.schemaplexai.model.entity.UserMemorySetting;

import java.util.List;

/**
 * 用户记忆服务
 */
public interface UserMemoryService {

    UserMemorySetting getOrCreateSetting(String tenantId, String userId);

    UserMemorySetting updateSetting(String tenantId, String userId, UserMemorySettingUpdateRequest request);

    UserMemory createExplicitMemory(String tenantId, String userId, UserMemoryCreateRequest request);

    List<UserMemory> list(String tenantId, String userId, UserMemoryQueryRequest request);

    UserMemory update(String tenantId, String userId, String memoryId, UserMemoryUpdateRequest request);

    void delete(String tenantId, String userId, String memoryId);

    List<UserMemory> findStaticMemories(String tenantId, String userId, String agentId, String projectId, int limit);

    List<UserMemory> findContextualMemories(String tenantId, String userId, String agentId,
                                            String projectId, String query, int limit);
}
