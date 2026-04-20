package com.schemaplexai.service.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.MemoryPhaseEnum;
import com.schemaplexai.common.enums.MemoryTypeEnum;
import com.schemaplexai.dao.mapper.AgentMemoryMapper;
import com.schemaplexai.model.entity.AgentMemory;
import com.schemaplexai.service.agent.execution.AgentModelInvoker;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent记忆提取服务（Phase 1）
 * <p>参考 Codex CLI 的两阶段记忆管线：
 * Phase 1 使用小模型从对话历史中提取结构化记忆，
 * 成本低、速度快，在每次执行完成后异步触发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMemoryExtractionService {

    private static final int MIN_MESSAGES_FOR_EXTRACTION = 4;
    private static final int MAX_MEMORIES_PER_EXTRACTION = 8;
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final String EXTRACTION_PROMPT = """
            你是一个记忆提取助手。从以下对话历史中提取值得长期记住的关键信息。

            提取规则：
            1. 只提取对未来任务有价值的信息，忽略临时性、一次性的内容
            2. 每条记忆必须是独立的、自包含的事实陈述
            3. 按以下类型分类：
               - FACT: 关于项目、代码库、架构的客观事实
               - PREFERENCE: 用户的偏好、习惯、工作方式
               - CONSTRAINT: 技术约束、业务规则、安全限制
               - PATTERN: 反复出现的问题模式、解决方案模式

            输出格式（每条一行）：
            [类型] 记忆内容

            示例：
            [FACT] 项目使用 MyBatis-Plus 作为 ORM，表名前缀为 sf_
            [PREFERENCE] 用户偏好中文注释和英文变量名
            [CONSTRAINT] PostgreSQL 逻辑删除字段必须为 deleted INTEGER NOT NULL DEFAULT 0
            [PATTERN] 新增实体时需要同步创建 Mapper 接口和 Service 类

            如果没有值得提取的记忆，输出：[NONE]
            """;

    private static final Pattern MEMORY_LINE_PATTERN = Pattern.compile(
            "^\\[(FACT|PREFERENCE|CONSTRAINT|PATTERN)]\\s+(.+)$", Pattern.MULTILINE);

    private final AgentMemoryMapper memoryMapper;
    private final AIModelRouter modelRouter;

    /**
     * 异步提取记忆（执行完成后调用）
     */
    @Async("agentExecutorPool")
    public void extractMemoriesAsync(String tenantId, String agentId, String executionId,
                                     List<ChatMessage> conversationHistory) {
        try {
            extractMemories(tenantId, agentId, executionId, conversationHistory);
        } catch (Exception e) {
            log.warn("记忆提取失败: agentId={}, executionId={}, error={}", agentId, executionId, e.getMessage());
        }
    }

    /**
     * 同步提取记忆
     */
    public List<AgentMemory> extractMemories(String tenantId, String agentId, String executionId,
                                              List<ChatMessage> conversationHistory) {
        if (conversationHistory == null || conversationHistory.size() < MIN_MESSAGES_FOR_EXTRACTION) {
            log.debug("对话历史过短，跳过记忆提取: agentId={}, messageCount={}",
                    agentId, conversationHistory != null ? conversationHistory.size() : 0);
            return List.of();
        }

        String historyText = buildHistoryText(conversationHistory);
        if (!StringUtils.hasText(historyText)) {
            return List.of();
        }

        // 使用小模型提取
        AiModelConfig compactModel = modelRouter.resolveCompactModel(tenantId);
        if (compactModel == null) {
            log.debug("未配置压缩模型，使用字符串提取降级: agentId={}", agentId);
            return extractByRegex(tenantId, agentId, executionId, conversationHistory);
        }

        try {
            ChatModel model = modelRouter.buildChatModel(compactModel);
            ChatResponse response = model.chat(List.of(
                    SystemMessage.from(EXTRACTION_PROMPT),
                    UserMessage.from(historyText)
            ));

            String output = response.aiMessage().text();
            if (!StringUtils.hasText(output) || output.contains("[NONE]")) {
                log.debug("模型未提取到有价值的记忆: agentId={}", agentId);
                return List.of();
            }

            List<AgentMemory> memories = parseMemories(tenantId, agentId, executionId, output,
                    conversationHistory.size());
            for (AgentMemory memory : memories) {
                memoryMapper.insert(memory);
            }
            log.info("记忆提取完成: agentId={}, executionId={}, count={}", agentId, executionId, memories.size());
            return memories;
        } catch (Exception e) {
            log.warn("模型记忆提取失败，降级到正则提取: error={}", e.getMessage());
            return extractByRegex(tenantId, agentId, executionId, conversationHistory);
        }
    }

    /**
     * 查询Agent的长期记忆（供ContextInjector使用）
     */
    public List<AgentMemory> getActiveMemories(String tenantId, String agentId, int limit) {
        return memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getTenantId, tenantId)
                .eq(AgentMemory::getAgentId, agentId)
                .eq(AgentMemory::getPhase, MemoryPhaseEnum.CONSOLIDATED.getCode())
                .isNull(AgentMemory::getConsolidatedInto)
                .and(w -> w.isNull(AgentMemory::getExpiresAt)
                        .or().gt(AgentMemory::getExpiresAt, LocalDateTime.now()))
                .orderByDesc(AgentMemory::getRelevanceScore)
                .last("LIMIT " + limit));
    }

    /**
     * 查询Agent的所有记忆（管理页面用）
     */
    public List<AgentMemory> listMemories(String tenantId, String agentId) {
        return memoryMapper.selectList(new LambdaQueryWrapper<AgentMemory>()
                .eq(AgentMemory::getTenantId, tenantId)
                .eq(AgentMemory::getAgentId, agentId)
                .orderByDesc(AgentMemory::getCreatedAt));
    }

    public void deleteMemory(String id) {
        memoryMapper.deleteById(id);
    }

    private String buildHistoryText(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        int charBudget = 8000;
        for (int i = messages.size() - 1; i >= 0 && sb.length() < charBudget; i--) {
            ChatMessage msg = messages.get(i);
            String role = msg instanceof UserMessage ? "用户" :
                    msg instanceof AiMessage ? "AI" : "系统";
            String text = msg instanceof UserMessage um ? um.singleText() :
                    msg instanceof AiMessage am ? am.text() : msg.toString();
            if (StringUtils.hasText(text)) {
                sb.insert(0, role + ": " + truncate(text, 1000) + "\n\n");
            }
        }
        return sb.toString().trim();
    }

    private List<AgentMemory> parseMemories(String tenantId, String agentId, String executionId,
                                             String output, int turnCount) {
        List<AgentMemory> memories = new ArrayList<>();
        Matcher matcher = MEMORY_LINE_PATTERN.matcher(output);
        while (matcher.find() && memories.size() < MAX_MEMORIES_PER_EXTRACTION) {
            String type = matcher.group(1);
            String content = matcher.group(2).trim();
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH);
            }
            AgentMemory memory = new AgentMemory();
            memory.setTenantId(tenantId);
            memory.setAgentId(agentId);
            memory.setExecutionId(executionId);
            memory.setMemoryType(type);
            memory.setPhase(MemoryPhaseEnum.RAW.getCode());
            memory.setContent(content);
            memory.setRelevanceScore(BigDecimal.ONE);
            memory.setSourceTurnCount(turnCount);
            memories.add(memory);
        }
        return memories;
    }

    /**
     * 降级：正则提取（不调用模型）
     */
    private List<AgentMemory> extractByRegex(String tenantId, String agentId, String executionId,
                                              List<ChatMessage> messages) {
        List<AgentMemory> memories = new ArrayList<>();
        for (ChatMessage msg : messages) {
            if (!(msg instanceof UserMessage um)) continue;
            String text = um.singleText();
            if (!StringUtils.hasText(text) || text.length() < 20) continue;
            // 提取包含"必须"、"不要"、"总是"等约束性关键词的用户消息
            if (text.contains("必须") || text.contains("不要") || text.contains("总是")
                    || text.contains("禁止") || text.contains("规则")) {
                AgentMemory memory = new AgentMemory();
                memory.setTenantId(tenantId);
                memory.setAgentId(agentId);
                memory.setExecutionId(executionId);
                memory.setMemoryType(MemoryTypeEnum.CONSTRAINT.getCode());
                memory.setPhase(MemoryPhaseEnum.RAW.getCode());
                memory.setContent(truncate(text, MAX_CONTENT_LENGTH));
                memory.setRelevanceScore(new BigDecimal("0.60"));
                memory.setSourceTurnCount(messages.size());
                memories.add(memory);
                if (memories.size() >= 3) break;
            }
        }
        if (!memories.isEmpty()) {
            memories.forEach(memoryMapper::insert);
            log.info("正则降级记忆提取: agentId={}, count={}", agentId, memories.size());
        }
        return memories;
    }

    private String truncate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}