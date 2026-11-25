package com.schemaplexai.service.user.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.UserMemoryKindEnum;
import com.schemaplexai.common.enums.UserMemoryScopeEnum;
import com.schemaplexai.common.enums.UserMemorySensitivityEnum;
import com.schemaplexai.common.enums.UserMemorySourceTypeEnum;
import com.schemaplexai.common.enums.UserMemoryStatusEnum;
import com.schemaplexai.dao.mapper.UserMemoryMapper;
import com.schemaplexai.dao.mapper.UserMemorySettingMapper;
import com.schemaplexai.model.entity.UserMemory;
import com.schemaplexai.model.entity.UserMemorySetting;
import com.schemaplexai.service.ai.ChatMessageText;
import com.schemaplexai.service.memory.MemoryExtractionWriter;
import com.schemaplexai.service.memory.MemoryExtractionWriter.Extractor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户记忆提取服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserMemoryExtractionService {

    private static final int MAX_EXPLICIT_MEMORIES = 3;
    private static final Pattern EXPLICIT_MEMORY_PATTERN = Pattern.compile(
            "^\\h*(?:(?:请\\h*)?(?:帮我记住|记住|以后(?:都|默认)?|默认|我偏好|我喜欢|(不要再))"
                    + "|(?:please\\h+)?remember\\b(?:\\h+(?:that|this)\\b)?|from now on\\b|my preference is\\b)"
                    + "[:：,，\\h]*(.+)",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final UserMemoryMapper userMemoryMapper;
    private final UserMemorySettingMapper userMemorySettingMapper;
    private final MemoryExtractionWriter extractionWriter;

    /** 在执行完成通知发出前保存规则提取的记忆，新会话可以立即读取。 */
    public void saveAfterCompletion(String tenantId, String userId, String agentId, String projectId, String executionId,
                                    String conversationId, boolean temporaryChat, Boolean memoryWriteEnabled,
                                    Supplier<List<ChatMessage>> authoredHistory) {
        if (temporaryChat || Boolean.FALSE.equals(memoryWriteEnabled) || !StringUtils.hasText(tenantId)
                || !StringUtils.hasText(userId) || !StringUtils.hasText(conversationId)) {
            return;
        }
        try {
            List<UserMemory> saved = extractMemories(tenantId, userId, agentId, projectId, executionId, conversationId,
                    authoredHistory.get(), false);
            log.info("用户显式记忆提取: executionId={}, agentId={}, savedCount={}", executionId, agentId, saved.size());
        } catch (RuntimeException exception) {
            log.warn("用户显式记忆提取失败: executionId={}, agentId={}, errorType={}",
                    executionId, agentId, exception.getClass().getSimpleName());
        }
    }

    public List<UserMemory> extractMemories(String tenantId, String userId, String agentId, String projectId, String executionId,
                                            String conversationId, List<ChatMessage> conversationHistory,
                                            boolean temporaryChat) {
        if (temporaryChat || !StringUtils.hasText(tenantId) || !StringUtils.hasText(userId)
                || conversationHistory == null || conversationHistory.isEmpty()) {
            return List.of();
        }

        List<UserMemory> memories = new ArrayList<>();
        Set<String> collected = new LinkedHashSet<>();
        for (ChatMessage message : conversationHistory) {
            if (!(message instanceof UserMessage userMessage)) {
                continue;
            }
            String content = extractExplicitContent(ChatMessageText.textOf(userMessage));
            if (!StringUtils.hasText(content) || !collected.add(content)) {
                continue;
            }
            memories.add(buildExplicitMemory(tenantId, userId, agentId, projectId, executionId, conversationId, content));
            if (memories.size() >= MAX_EXPLICIT_MEMORIES) {
                break;
            }
        }
        if (memories.isEmpty() || !isWritable(tenantId, userId)) {
            return List.of();
        }
        return extractionWriter.writeOnce(executionId, Extractor.USER, memories, userMemoryMapper::insert);
    }

    private boolean isWritable(String tenantId, String userId) {
        UserMemorySetting setting = userMemorySettingMapper.selectOne(new LambdaQueryWrapper<UserMemorySetting>()
                .eq(UserMemorySetting::getTenantId, tenantId)
                .eq(UserMemorySetting::getUserId, userId)
                .last("LIMIT 1"));
        if (setting == null) {
            return true;
        }
        return Boolean.TRUE.equals(setting.getMemoryEnabled())
                && Boolean.TRUE.equals(setting.getAutoExtractEnabled());
    }

    private String extractExplicitContent(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = EXPLICIT_MEMORY_PATTERN.matcher(text.trim());
        if (!matcher.find()) {
            return null;
        }
        String instruction = matcher.group(1);
        String content = matcher.group(2).trim();
        if (!StringUtils.hasText(content) || content.length() < 4) {
            return null;
        }
        if (instruction != null) {
            content = instruction + content;
        }
        return content.length() > 500 ? content.substring(0, 500) : content;
    }

    private UserMemory buildExplicitMemory(String tenantId, String userId, String agentId, String projectId,
                                           String executionId, String conversationId, String content) {
        UserMemory memory = new UserMemory();
        memory.setTenantId(tenantId);
        memory.setUserId(userId);
        memory.setAgentId(StringUtils.hasText(agentId) ? agentId : null);
        memory.setExecutionId(executionId);
        memory.setConversationId(conversationId);
        if (StringUtils.hasText(projectId)) {
            memory.setProjectId(projectId);
            memory.setMemoryScope(UserMemoryScopeEnum.USER_PROJECT.getCode());
        } else {
            memory.setMemoryScope(StringUtils.hasText(agentId)
                    ? UserMemoryScopeEnum.USER_AGENT.getCode()
                    : UserMemoryScopeEnum.USER_GLOBAL.getCode());
        }
        memory.setMemoryKind(classifyKind(content));
        memory.setSourceType(UserMemorySourceTypeEnum.EXPLICIT.getCode());
        memory.setStatus(UserMemoryStatusEnum.ACTIVE.getCode());
        memory.setContent(content);
        memory.setContentHash(sha256(tenantId + "|" + userId + "|" + content));
        memory.setConfidenceScore(BigDecimal.ONE);
        memory.setImportanceScore(new BigDecimal("0.80"));
        memory.setRelevanceScore(BigDecimal.ONE);
        memory.setSensitivityLevel(UserMemorySensitivityEnum.NORMAL.getCode());
        memory.setPinned(false);
        memory.setUseCount(0);
        return memory;
    }

    private String classifyKind(String content) {
        if (content.contains("不要") || content.contains("避免") || content.contains("别")) {
            return UserMemoryKindEnum.AVOIDANCE.getCode();
        }
        if (content.contains("工具") || content.contains("技能")) {
            return UserMemoryKindEnum.TOOL_PREFERENCE.getCode();
        }
        if (content.contains("先") || content.contains("流程") || content.contains("步骤")) {
            return UserMemoryKindEnum.WORKFLOW_RULE.getCode();
        }
        if (content.contains("语气") || content.contains("风格") || content.contains("简洁") || content.contains("详细")) {
            return UserMemoryKindEnum.COMMUNICATION_STYLE.getCode();
        }
        return UserMemoryKindEnum.PREFERENCE.getCode();
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
