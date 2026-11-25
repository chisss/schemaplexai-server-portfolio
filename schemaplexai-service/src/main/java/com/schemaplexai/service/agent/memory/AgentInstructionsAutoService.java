package com.schemaplexai.service.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.dao.mapper.ContextItemMapper;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.AgentMemory;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Agent 专属指令自动生成与更新服务
 * <p>首次执行时自动创建专属指令上下文；每次执行完成后从记忆中压缩更新。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentInstructionsAutoService {

    private static final String GENERATED_TITLE = "Agent 专属指令（自动生成）";
    private static final String MEMORY_INSTRUCTIONS_METADATA_KEY = "derivedFromAgentMemory";
    private static final String INSTRUCTIONS_INPUT_DIGEST = "agentMemoryInstructionsInputSha256";
    private static final String INSTRUCTIONS_OUTPUT_DIGEST = "agentMemoryInstructionsOutputSha256";
    private static final String SYSTEM_PROMPT = "你是一个精确的 Agent 指令生成助手。";

    private static final int MAX_INSTRUCTIONS_LINES = 100;
    private static final BigDecimal HIGH_RELEVANCE_THRESHOLD = new BigDecimal("1.0");
    private static final BigDecimal FALLBACK_RELEVANCE_THRESHOLD = new BigDecimal("0.80");
    private static final int MIN_MEMORIES_FOR_UPDATE = 3;
    private static final int MAX_MEMORIES_TO_FETCH = 30;

    private static final String INIT_CONTENT = """
            # Agent 专属指令（自动生成）

            ## 功能定位
            （待首次执行后自动填充）

            ## 执行偏好
            （待首次执行后自动填充）
            """;

    private static final String COMPRESS_PROMPT = """
            你是一个 Agent 专属指令生成助手。根据以下记忆条目，生成一份精炼的 Agent 专属指令文档。

            要求：
            1. 使用 Markdown 格式
            2. 总行数严格不超过 %d 行
            3. 包含以下章节（无内容的章节可省略）：
               - **功能定位**：1-2 句话概括此 Agent 的核心职责
               - **常用技能/工具**：列出此 Agent 最常使用的工具和技能
               - **踩过的坑/约束**：记录重要的技术约束、已知问题、注意事项
               - **执行偏好**：用户偏好的工作方式、输出风格等
            4. 只保留最关键、最有价值的信息，去除冗余
            5. 每条信息必须独立、自包含、可直接指导未来执行

            记忆条目：
            %s
            """;

    private final ContextEntityMapper contextEntityMapper;
    private final ContextItemMapper contextItemMapper;
    private final AgentContextBindingMapper agentContextBindingMapper;
    private final AgentMemoryExtractionService memoryExtractionService;
    private final AIModelRouter modelRouter;
    private final Set<InstructionScope> updating = ConcurrentHashMap.newKeySet();

    /**
     * 首次执行时自动创建专属指令（幂等）
     */
    public void autoInitIfAbsent(String tenantId, String agentId) {
        ContextItem existing = findInstructionsItem(agentId);
        if (existing != null) {
            return;
        }

        // 查找已有的 agent 级别上下文绑定
        AgentContextBinding binding = agentContextBindingMapper.selectOne(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId)
                        .isNotNull(AgentContextBinding::getContextId)
                        .last("LIMIT 1"));

        String contextId;
        if (binding != null) {
            contextId = binding.getContextId();
        } else {
            // 自动创建上下文和绑定
            var context = new ContextEntity();
            context.setName("Agent 专属指令上下文");
            context.setContextLevel("agent");
            context.setProjectId(agentId);
            context.setStatus(CommonConstant.STATUS_ACTIVE);
            contextEntityMapper.insert(context);
            contextId = context.getId();

            binding = new AgentContextBinding();
            binding.setAgentId(agentId);
            binding.setContextId(contextId);
            binding.setSourceType("manual");
            binding.setTitle(GENERATED_TITLE);
            binding.setSortOrder(0);
            binding.setStatus(CommonConstant.STATUS_ACTIVE);
            agentContextBindingMapper.insert(binding);
            log.info("自动创建Agent上下文绑定: agentId={}, contextId={}", agentId, contextId);
        }

        // 创建专属指令 ContextItem
        ContextItem item = new ContextItem();
        item.setContextId(contextId);
        item.setItemType("config");
        item.setTitle(GENERATED_TITLE);
        item.setContent(INIT_CONTENT);
        item.setIsAgentInstructions(true);
        item.setSortOrder(0);
        contextItemMapper.insert(item);
        log.info("自动初始化Agent专属指令: agentId={}, itemId={}", agentId, item.getId());
    }

    /**
     * 异步从记忆中压缩更新专属指令
     */
    @Async("agentExecutorPool")
    public void updateFromMemoriesAsync(String tenantId, String agentId, String executionId) {
        var scope = new InstructionScope(tenantId, agentId);
        if (!updating.add(scope)) {
            return;
        }
        try {
            updateFromMemories(tenantId, agentId, executionId);
        } catch (Exception e) {
            log.warn("Agent专属指令自动更新失败: agentId={}, executionId={}, errorType={}",
                    agentId, executionId, e.getClass().getSimpleName());
        } finally {
            updating.remove(scope);
        }
    }

    private void updateFromMemories(String tenantId, String agentId, String executionId) {
        List<AgentMemory> allMemories = memoryExtractionService.getActiveMemories(tenantId, agentId, MAX_MEMORIES_TO_FETCH);
        if (CollectionUtils.isEmpty(allMemories)) {
            return;
        }

        // 优先取高相关性记忆
        List<AgentMemory> memories = allMemories.stream()
                .filter(m -> m.getRelevanceScore() != null && m.getRelevanceScore().compareTo(HIGH_RELEVANCE_THRESHOLD) >= 0)
                .toList();
        // 不足则放宽阈值
        if (memories.size() < MIN_MEMORIES_FOR_UPDATE) {
            memories = allMemories.stream()
                    .filter(m -> m.getRelevanceScore() != null && m.getRelevanceScore().compareTo(FALLBACK_RELEVANCE_THRESHOLD) >= 0)
                    .toList();
        }
        if (memories.size() < MIN_MEMORIES_FOR_UPDATE) {
            return;
        }

        ContextItem instructionsItem = findInstructionsItem(agentId);
        if (instructionsItem == null || !isMemoryDerivedInstruction(instructionsItem)) {
            return;
        }
        Map<String, Object> metadata = instructionsItem.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(instructionsItem.getMetadata());
        Object previousOutput = metadata.get(INSTRUCTIONS_OUTPUT_DIGEST);
        if (previousOutput != null && !previousOutput.equals(digest(instructionsItem.getContent()))) {
            return;
        }

        AiModelConfig model = modelRouter.resolveCompactModel(tenantId);
        if (model == null) {
            model = modelRouter.resolvePrimaryModel(tenantId);
        }
        if (model == null) {
            log.warn("无可用模型，跳过专属指令更新: agentId={}", agentId);
            return;
        }

        String memoriesText = memories.stream()
                .sorted(Comparator.comparing(AgentMemory::getRelevanceScore).reversed()
                        .thenComparing(AgentInstructionsAutoService::formatMemory))
                .map(AgentInstructionsAutoService::formatMemory)
                .collect(Collectors.joining("\n"));

        String prompt = String.format(COMPRESS_PROMPT, MAX_INSTRUCTIONS_LINES, memoriesText);
        String inputDigest = digest(SYSTEM_PROMPT, prompt, model.getConfigId(), model.getProvider(),
                model.getProtocol(), model.getBaseUrl(), model.getModelId(),
                Integer.toString(model.getMaxTokens()), model.getReasoningStrength());
        if (previousOutput != null && inputDigest.equals(metadata.get(INSTRUCTIONS_INPUT_DIGEST))) {
            log.debug("复用未变化的Agent专属指令: agentId={}, executionId={}", agentId, executionId);
            return;
        }

        var observedAt = instructionsItem.getUpdatedAt();
        ChatModel chatModel = modelRouter.buildChatModel(model);
        ChatResponse response = chatModel.chat(List.of(SystemMessage.from(SYSTEM_PROMPT), UserMessage.from(prompt)));
        var usage = response.tokenUsage();
        log.info("Agent专属指令模型已响应: agentId={}, executionId={}, inputTokens={}, outputTokens={}",
                agentId, executionId, usage == null ? null : usage.inputTokenCount(),
                usage == null ? null : usage.outputTokenCount());
        String compressed = response.aiMessage().text();
        if (!StringUtils.hasText(compressed)) {
            return;
        }

        compressed = truncateToMaxLines(compressed, MAX_INSTRUCTIONS_LINES);
        metadata.put(MEMORY_INSTRUCTIONS_METADATA_KEY, true);
        metadata.put(INSTRUCTIONS_INPUT_DIGEST, inputDigest);
        metadata.put(INSTRUCTIONS_OUTPUT_DIGEST, digest(compressed));
        ContextItem update = new ContextItem();
        update.setContent(compressed);
        update.setMetadata(metadata);
        var unchanged = new LambdaUpdateWrapper<ContextItem>()
                .eq(ContextItem::getId, instructionsItem.getId());
        if (observedAt == null) {
            unchanged.isNull(ContextItem::getUpdatedAt);
        } else {
            unchanged.eq(ContextItem::getUpdatedAt, observedAt);
        }
        if (contextItemMapper.update(update, unchanged) == 1) {
            log.info("Agent专属指令已更新: agentId={}, lines={}", agentId, compressed.lines().count());
        }
    }

    private static String formatMemory(AgentMemory memory) {
        return "[" + memory.getMemoryType() + "] " + memory.getContent();
    }

    private static String digest(String... values) {
        var data = new StringBuilder();
        for (String value : values) {
            String part = Objects.toString(value, "");
            data.append(part.length()).append(':').append(part);
        }
        return HashUtils.sha256Hex(data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private record InstructionScope(String tenantId, String agentId) {
    }

    /** 保留已有自动生成项的识别方式，并为新更新记录显式来源。 */
    public static boolean isMemoryDerivedInstruction(ContextItem item) {
        return GENERATED_TITLE.equals(item.getTitle())
                || (item.getMetadata() != null
                && Boolean.TRUE.equals(item.getMetadata().get(MEMORY_INSTRUCTIONS_METADATA_KEY)));
    }

    private ContextItem findInstructionsItem(String agentId) {
        List<AgentContextBinding> bindings = agentContextBindingMapper.selectList(
                new LambdaQueryWrapper<AgentContextBinding>()
                        .eq(AgentContextBinding::getAgentId, agentId));
        List<String> contextIds = bindings.stream()
                .map(AgentContextBinding::getContextId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (CollectionUtils.isEmpty(contextIds)) {
            return null;
        }
        return contextItemMapper.selectOne(
                new LambdaQueryWrapper<ContextItem>()
                        .in(ContextItem::getContextId, contextIds)
                        .eq(ContextItem::getIsAgentInstructions, true)
                        .last("LIMIT 1"));
    }

    private String truncateToMaxLines(String text, int maxLines) {
        List<String> lines = text.lines().toList();
        if (lines.size() <= maxLines) {
            return text;
        }
        return lines.stream().limit(maxLines).collect(Collectors.joining("\n"));
    }
}
