package com.schemaplexai.service.user.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.enums.UserMemoryKindEnum;
import com.schemaplexai.common.enums.UserMemoryScopeEnum;
import com.schemaplexai.common.enums.UserMemorySensitivityEnum;
import com.schemaplexai.common.enums.UserMemorySourceTypeEnum;
import com.schemaplexai.common.enums.UserMemoryStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.UserMemoryMapper;
import com.schemaplexai.dao.mapper.UserMemoryProfileMapper;
import com.schemaplexai.dao.mapper.UserMemorySettingMapper;
import com.schemaplexai.model.dto.user.UserMemoryCreateRequest;
import com.schemaplexai.model.dto.user.UserMemoryQueryRequest;
import com.schemaplexai.model.dto.user.UserMemorySettingUpdateRequest;
import com.schemaplexai.model.dto.user.UserMemoryUpdateRequest;
import com.schemaplexai.model.entity.UserMemory;
import com.schemaplexai.model.entity.UserMemoryProfile;
import com.schemaplexai.model.entity.UserMemorySetting;
import com.schemaplexai.service.user.UserMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * 用户记忆服务实现
 */
@Service
@RequiredArgsConstructor
public class UserMemoryServiceImpl implements UserMemoryService {

    private static final Set<String> STATIC_MEMORY_KINDS = Set.of(
            UserMemoryKindEnum.PROFILE_FACT.getCode(),
            UserMemoryKindEnum.PREFERENCE.getCode(),
            UserMemoryKindEnum.COMMUNICATION_STYLE.getCode(),
            UserMemoryKindEnum.WORKFLOW_RULE.getCode(),
            UserMemoryKindEnum.TOOL_PREFERENCE.getCode(),
            UserMemoryKindEnum.AVOIDANCE.getCode()
    );

    private final UserMemoryMapper userMemoryMapper;
    private final UserMemorySettingMapper userMemorySettingMapper;
    private final UserMemoryProfileMapper userMemoryProfileMapper;

    @Override
    public UserMemorySetting getOrCreateSetting(String tenantId, String userId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(userId)) {
            return defaultSetting(tenantId, userId);
        }
        UserMemorySetting setting = userMemorySettingMapper.selectOne(new LambdaQueryWrapper<UserMemorySetting>()
                .eq(UserMemorySetting::getTenantId, tenantId)
                .eq(UserMemorySetting::getUserId, userId)
                .last("LIMIT 1"));
        if (setting != null) {
            return setting;
        }
        UserMemorySetting created = defaultSetting(tenantId, userId);
        userMemorySettingMapper.insert(created);
        return created;
    }

    @Override
    public UserMemorySetting updateSetting(String tenantId, String userId, UserMemorySettingUpdateRequest request) {
        UserMemorySetting setting = getOrCreateSetting(tenantId, userId);
        if (request.getMemoryEnabled() != null) {
            setting.setMemoryEnabled(request.getMemoryEnabled());
        }
        if (request.getReferenceSavedMemory() != null) {
            setting.setReferenceSavedMemory(request.getReferenceSavedMemory());
        }
        if (request.getReferenceChatHistory() != null) {
            setting.setReferenceChatHistory(request.getReferenceChatHistory());
        }
        if (request.getAutoExtractEnabled() != null) {
            setting.setAutoExtractEnabled(request.getAutoExtractEnabled());
        }
        if (StringUtils.hasText(request.getSensitiveMemoryPolicy())) {
            setting.setSensitiveMemoryPolicy(request.getSensitiveMemoryPolicy().trim());
        }
        if (request.getRetentionDays() != null) {
            setting.setRetentionDays(request.getRetentionDays());
        }
        userMemorySettingMapper.updateById(setting);
        return setting;
    }

    @Override
    public UserMemory createExplicitMemory(String tenantId, String userId, UserMemoryCreateRequest request) {
        UserMemory memory = new UserMemory();
        memory.setTenantId(tenantId);
        memory.setUserId(userId);
        memory.setAgentId(trimToNull(request.getAgentId()));
        memory.setProjectId(trimToNull(request.getProjectId()));
        memory.setWorkspaceId(trimToNull(request.getWorkspaceId()));
        memory.setMemoryScope(resolveScope(request.getMemoryScope(), memory.getAgentId(), memory.getProjectId(), memory.getWorkspaceId()));
        memory.setMemoryKind(resolveKind(request.getMemoryKind()));
        memory.setSourceType(UserMemorySourceTypeEnum.EXPLICIT.getCode());
        memory.setStatus(UserMemoryStatusEnum.ACTIVE.getCode());
        memory.setContent(normalizeContent(request.getContent()));
        memory.setStructuredValue(request.getStructuredValue());
        memory.setContentHash(sha256(memory.getTenantId() + "|" + memory.getUserId() + "|" + memory.getContent()));
        memory.setConfidenceScore(new BigDecimal("1.00"));
        memory.setImportanceScore(Boolean.TRUE.equals(request.getPinned()) ? new BigDecimal("0.90") : new BigDecimal("0.70"));
        memory.setRelevanceScore(BigDecimal.ONE);
        memory.setSensitivityLevel(UserMemorySensitivityEnum.NORMAL.getCode());
        memory.setPinned(Boolean.TRUE.equals(request.getPinned()));
        memory.setUseCount(0);
        memory.setExpiresAt(request.getExpiresAt());
        userMemoryMapper.insert(memory);
        return memory;
    }

    @Override
    public List<UserMemory> list(String tenantId, String userId, UserMemoryQueryRequest request) {
        LambdaQueryWrapper<UserMemory> query = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getUserId, userId);
        if (StringUtils.hasText(request.getAgentId())) {
            query.eq(UserMemory::getAgentId, request.getAgentId().trim());
        }
        if (StringUtils.hasText(request.getMemoryScope())) {
            query.eq(UserMemory::getMemoryScope, request.getMemoryScope().trim());
        }
        if (StringUtils.hasText(request.getMemoryKind())) {
            query.eq(UserMemory::getMemoryKind, request.getMemoryKind().trim());
        }
        if (StringUtils.hasText(request.getStatus())) {
            query.eq(UserMemory::getStatus, request.getStatus().trim());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            query.like(UserMemory::getContent, request.getKeyword().trim());
        }
        return userMemoryMapper.selectList(query
                .orderByDesc(UserMemory::getPinned)
                .orderByDesc(UserMemory::getUpdatedAt)
                .orderByDesc(UserMemory::getCreatedAt));
    }

    @Override
    public UserMemory update(String tenantId, String userId, String memoryId, UserMemoryUpdateRequest request) {
        UserMemory memory = requireOwnedMemory(tenantId, userId, memoryId);
        if (StringUtils.hasText(request.getMemoryScope())) {
            memory.setMemoryScope(request.getMemoryScope().trim());
        }
        if (StringUtils.hasText(request.getMemoryKind())) {
            memory.setMemoryKind(request.getMemoryKind().trim());
        }
        if (StringUtils.hasText(request.getStatus())) {
            memory.setStatus(request.getStatus().trim());
        }
        if (StringUtils.hasText(request.getContent())) {
            memory.setContent(normalizeContent(request.getContent()));
            memory.setContentHash(sha256(memory.getTenantId() + "|" + memory.getUserId() + "|" + memory.getContent()));
        }
        if (request.getStructuredValue() != null) {
            memory.setStructuredValue(request.getStructuredValue());
        }
        if (request.getPinned() != null) {
            memory.setPinned(request.getPinned());
        }
        if (request.getExpiresAt() != null) {
            memory.setExpiresAt(request.getExpiresAt());
        }
        userMemoryMapper.updateById(memory);
        return memory;
    }

    @Override
    public void delete(String tenantId, String userId, String memoryId) {
        requireOwnedMemory(tenantId, userId, memoryId);
        userMemoryMapper.deleteById(memoryId);
    }

    @Override
    public List<UserMemory> findStaticMemories(String tenantId, String userId, String agentId, String projectId, int limit) {
        if (!isReadable(tenantId, userId)) {
            return List.of();
        }
        return userMemoryMapper.selectList(baseActiveScopeQuery(tenantId, userId, agentId, projectId)
                .in(UserMemory::getMemoryKind, STATIC_MEMORY_KINDS)
                .orderByDesc(UserMemory::getPinned)
                .orderByDesc(UserMemory::getImportanceScore)
                .orderByDesc(UserMemory::getUpdatedAt)
                .last("LIMIT " + safeLimit(limit)));
    }

    @Override
    public List<UserMemory> findContextualMemories(String tenantId, String userId, String agentId,
                                                   String projectId, String queryText, int limit) {
        if (!isReadable(tenantId, userId) || !StringUtils.hasText(queryText)) {
            return List.of();
        }
        String keyword = queryText.trim();
        return userMemoryMapper.selectList(baseActiveScopeQuery(tenantId, userId, agentId, projectId)
                .like(UserMemory::getContent, keyword.length() > 80 ? keyword.substring(0, 80) : keyword)
                .orderByDesc(UserMemory::getRelevanceScore)
                .orderByDesc(UserMemory::getUpdatedAt)
                .last("LIMIT " + safeLimit(limit)));
    }

    public UserMemoryProfile findProfile(String tenantId, String userId, String agentId, String projectId, String profileScope) {
        return userMemoryProfileMapper.selectOne(new LambdaQueryWrapper<UserMemoryProfile>()
                .eq(UserMemoryProfile::getTenantId, tenantId)
                .eq(UserMemoryProfile::getUserId, userId)
                .eq(UserMemoryProfile::getProfileScope, profileScope)
                .eq(StringUtils.hasText(agentId), UserMemoryProfile::getAgentId, agentId)
                .eq(StringUtils.hasText(projectId), UserMemoryProfile::getProjectId, projectId)
                .last("LIMIT 1"));
    }

    private LambdaQueryWrapper<UserMemory> baseActiveScopeQuery(String tenantId, String userId, String agentId, String projectId) {
        LambdaQueryWrapper<UserMemory> query = new LambdaQueryWrapper<UserMemory>()
                .eq(UserMemory::getTenantId, tenantId)
                .eq(UserMemory::getUserId, userId)
                .eq(UserMemory::getStatus, UserMemoryStatusEnum.ACTIVE.getCode())
                .and(w -> w.isNull(UserMemory::getExpiresAt)
                        .or().gt(UserMemory::getExpiresAt, LocalDateTime.now()));
        query.and(scope -> {
            scope.eq(UserMemory::getMemoryScope, UserMemoryScopeEnum.USER_GLOBAL.getCode())
                    .isNull(UserMemory::getAgentId);
            if (StringUtils.hasText(agentId)) {
                scope.or(s -> s.eq(UserMemory::getMemoryScope, UserMemoryScopeEnum.USER_AGENT.getCode())
                        .eq(UserMemory::getAgentId, agentId));
            }
            if (StringUtils.hasText(projectId)) {
                scope.or(s -> s.eq(UserMemory::getMemoryScope, UserMemoryScopeEnum.USER_PROJECT.getCode())
                        .eq(UserMemory::getProjectId, projectId));
            }
        });
        return query;
    }

    private UserMemory requireOwnedMemory(String tenantId, String userId, String memoryId) {
        UserMemory memory = userMemoryMapper.selectById(memoryId);
        if (memory == null
                || !tenantId.equals(memory.getTenantId())
                || !userId.equals(memory.getUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "用户记忆不存在");
        }
        return memory;
    }

    private boolean isReadable(String tenantId, String userId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(userId)) {
            return false;
        }
        UserMemorySetting setting = getOrCreateSetting(tenantId, userId);
        return Boolean.TRUE.equals(setting.getMemoryEnabled())
                && Boolean.TRUE.equals(setting.getReferenceSavedMemory());
    }

    private UserMemorySetting defaultSetting(String tenantId, String userId) {
        UserMemorySetting setting = new UserMemorySetting();
        setting.setTenantId(tenantId);
        setting.setUserId(userId);
        setting.setMemoryEnabled(true);
        setting.setReferenceSavedMemory(true);
        setting.setReferenceChatHistory(false);
        setting.setAutoExtractEnabled(true);
        setting.setSensitiveMemoryPolicy("EXPLICIT_ONLY");
        return setting;
    }

    private String resolveScope(String requestedScope, String agentId, String projectId, String workspaceId) {
        if (StringUtils.hasText(requestedScope)) {
            return requestedScope.trim();
        }
        if (StringUtils.hasText(workspaceId)) {
            return UserMemoryScopeEnum.USER_WORKSPACE.getCode();
        }
        if (StringUtils.hasText(projectId)) {
            return UserMemoryScopeEnum.USER_PROJECT.getCode();
        }
        if (StringUtils.hasText(agentId)) {
            return UserMemoryScopeEnum.USER_AGENT.getCode();
        }
        return UserMemoryScopeEnum.USER_GLOBAL.getCode();
    }

    private String resolveKind(String requestedKind) {
        return StringUtils.hasText(requestedKind)
                ? requestedKind.trim()
                : UserMemoryKindEnum.PREFERENCE.getCode();
    }

    private String normalizeContent(String content) {
        String normalized = content == null ? "" : content.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "记忆内容不能为空");
        }
        return normalized.length() > 2000 ? normalized.substring(0, 2000) : normalized;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return 10;
        }
        return Math.min(limit, 50);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
