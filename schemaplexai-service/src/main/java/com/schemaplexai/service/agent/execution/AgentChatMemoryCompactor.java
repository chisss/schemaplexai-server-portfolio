package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ChatMemory 压缩器（增强版：支持模型驱动摘要）
 *
 * <p>策略：
 * <ul>
 *   <li>底层窗口切换为 TokenWindow</li>
 *   <li>保留最近原文消息</li>
 *   <li>将更早轮次折叠为结构化历史摘要</li>
 *   <li>优先使用小模型生成高质量摘要，降级到字符串提取</li>
 * </ul>
 */
@Slf4j
final class AgentChatMemoryCompactor {

    private static final String SUMMARY_HEADER = "[历史摘要]";
    private static final String TOOL_SUMMARY_HEADER = "[历史工具摘要]";
    private static final int MIN_SUMMARIZE_MESSAGE_COUNT = 8;
    private static final int SUMMARY_CHAR_LIMIT = 1_500;
    private static final int TOOL_SUMMARY_CHAR_LIMIT = 1_200;
    private static final int LARGE_TOOL_RESULT_CHAR_THRESHOLD = 1_200;

    private static final String MODEL_SUMMARIZATION_PROMPT = """
            将以下对话历史压缩为简洁的结构化摘要。保留：
            1. 用户的核心目标和需求
            2. 已完成的关键操作和结果
            3. 重要的技术决策和约束
            4. 待处理的事项

            输出格式：
            目标: <一句话概括>
            已完成: <关键操作列表>
            决策: <重要决策>
            待处理: <未完成事项>

            保持简洁，总长度不超过800字。
            """;

    private final TokenEstimatorSupport tokenEstimatorSupport;
    private ChatModel compactionModel;

    AgentChatMemoryCompactor(TokenEstimatorSupport tokenEstimatorSupport) {
        this.tokenEstimatorSupport = tokenEstimatorSupport;
    }

    /**
     * 设置用于上下文压缩的小模型（可选，未设置时降级到字符串提取）
     */
    void setCompactionModel(ChatModel compactionModel) {
        this.compactionModel = compactionModel;
    }

    ChatMemory createChatMemory(String conversationId,
                                AgentEngineParams params,
                                AiModelConfig modelConfig,
                                ChatMemoryStore chatMemoryStore) {
        int memoryBudgetTokens = resolveMemoryBudgetTokens(params, modelConfig);
        return TokenWindowChatMemory.builder()
                .id(conversationId)
                .maxTokens(memoryBudgetTokens, tokenEstimatorSupport.resolveEstimator(modelConfig))
                .chatMemoryStore(chatMemoryStore)
                .build();
    }

    CompactionResult compactIfNeeded(ChatMemory chatMemory, AgentEngineParams params, AiModelConfig modelConfig) {
        if (chatMemory == null || chatMemory.messages() == null || chatMemory.messages().isEmpty()) {
            return CompactionResult.noop();
        }
        List<ChatMessage> messages = new ArrayList<>(chatMemory.messages());
        int beforeTokens = tokenEstimatorSupport.estimateMessages(modelConfig, messages);
        int memoryBudgetTokens = resolveMemoryBudgetTokens(params, modelConfig);
        int recentRawMessageCount = resolveRecentRawMessageCount(params);
        boolean tokenBudgetTight = beforeTokens >= (int) (memoryBudgetTokens * 0.58);
        boolean containsLargeToolResult = containsLargeToolResult(messages);
        if ((messages.size() < MIN_SUMMARIZE_MESSAGE_COUNT && !containsLargeToolResult)
                || (!tokenBudgetTight && !containsLargeToolResult)) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }

        int splitIndex = resolveSafeSplitIndex(messages, recentRawMessageCount);
        if (splitIndex >= messages.size() - 2) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }

        List<ChatMessage> historical = messages.subList(0, splitIndex);
        List<ChatMessage> recent = messages.subList(splitIndex, messages.size());

        // 优先使用模型驱动摘要，降级到字符串提取
        String summary = modelBasedSummarize(historical);
        if (!StringUtils.hasText(summary)) {
            summary = buildHistoricalSummary(historical);
        }
        if (!StringUtils.hasText(summary)) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }

        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(UserMessage.from(summary));
        compacted.addAll(recent);
        chatMemory.set(compacted);

        int afterTokens = tokenEstimatorSupport.estimateMessages(modelConfig, compacted);
        return new CompactionResult(true, beforeTokens, afterTokens, summary.length(), messages.size(), compacted.size());
    }

    /**
     * 在真正发给模型前清洗消息序列，避免窗口淘汰后残留非法的工具调用/工具结果组合。
     */
    NormalizationResult normalizeForRequest(ChatMemory chatMemory) {
        if (chatMemory == null || chatMemory.messages() == null || chatMemory.messages().isEmpty()) {
            return NormalizationResult.noop();
        }
        List<ChatMessage> original = new ArrayList<>(chatMemory.messages());
        List<ChatMessage> normalized = new ArrayList<>();
        int convertedSegments = 0;
        int droppedMessages = 0;

        for (int index = 0; index < original.size(); ) {
            ChatMessage current = original.get(index);
            if (current instanceof AiMessage aiMessage && isEmptyAiMessage(aiMessage)) {
                droppedMessages++;
                index++;
                continue;
            }
            if (current instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                ToolExchangeSegment segment = collectToolExchangeSegment(original, index, aiMessage);
                if (segment.complete()) {
                    normalized.add(aiMessage);
                    normalized.addAll(segment.resultMessages());
                } else {
                    String summary = buildToolExchangeSummary(aiMessage, segment.resultMessages());
                    if (StringUtils.hasText(summary)) {
                        normalized.add(UserMessage.from(summary));
                        convertedSegments++;
                    } else {
                        droppedMessages += 1 + segment.resultMessages().size();
                    }
                }
                index = segment.nextIndex();
                continue;
            }
            if (current instanceof ToolExecutionResultMessage) {
                List<ToolExecutionResultMessage> orphanResults = new ArrayList<>();
                int cursor = index;
                while (cursor < original.size() && original.get(cursor) instanceof ToolExecutionResultMessage toolMessage) {
                    orphanResults.add(toolMessage);
                    cursor++;
                }
                String summary = buildOrphanToolSummary(orphanResults);
                if (StringUtils.hasText(summary)) {
                    normalized.add(UserMessage.from(summary));
                    convertedSegments++;
                } else {
                    droppedMessages += orphanResults.size();
                }
                index = cursor;
                continue;
            }
            normalized.add(current);
            index++;
        }

        if (convertedSegments == 0 && droppedMessages == 0) {
            return new NormalizationResult(false, 0, 0, original.size(), original.size());
        }
        chatMemory.set(normalized);
        return new NormalizationResult(true, convertedSegments, droppedMessages, original.size(), normalized.size());
    }

    int resolveMemoryBudgetTokens(AgentEngineParams params, AiModelConfig modelConfig) {
        int contextWindowTokens = modelConfig != null ? modelConfig.resolvedContextWindowTokens() : 16_384;
        int outputReserveTokens = modelConfig != null && modelConfig.getMaxTokens() > 0 ? modelConfig.getMaxTokens() : 2_048;
        int target = Math.max(2_048, contextWindowTokens - outputReserveTokens - 1_536);
        int ceiling = Math.max(3_072, contextWindowTokens / 2);
        return Math.min(target, ceiling);
    }

    private int resolveRecentRawMessageCount(AgentEngineParams params) {
        if (params == null) {
            return 5;
        }
        return Math.max(4, Math.min(6, (params.getMaxToolCallsPerRound() / 2) + 1));
    }

    private boolean containsLargeToolResult(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage toolMessage
                    && StringUtils.hasText(toolMessage.text())
                    && toolMessage.text().length() >= LARGE_TOOL_RESULT_CHAR_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private int resolveSafeSplitIndex(List<ChatMessage> messages, int recentRawMessageCount) {
        int splitIndex = Math.max(2, messages.size() - recentRawMessageCount);
        while (splitIndex > 0 && splitIndex < messages.size()
                && messages.get(splitIndex) instanceof ToolExecutionResultMessage) {
            splitIndex--;
        }
        if (splitIndex <= 0) {
            return messages.size();
        }
        return splitIndex;
    }

    private ToolExchangeSegment collectToolExchangeSegment(List<ChatMessage> messages, int aiIndex, AiMessage aiMessage) {
        List<ToolExecutionResultMessage> resultMessages = new ArrayList<>();
        int cursor = aiIndex + 1;
        while (cursor < messages.size() && messages.get(cursor) instanceof ToolExecutionResultMessage toolMessage) {
            resultMessages.add(toolMessage);
            cursor++;
        }
        return new ToolExchangeSegment(resultMessages, cursor, isCompleteToolExchange(aiMessage, resultMessages));
    }

    private boolean isCompleteToolExchange(AiMessage aiMessage, List<ToolExecutionResultMessage> resultMessages) {
        List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
        if (toolRequests == null || toolRequests.isEmpty()) {
            return true;
        }
        if (resultMessages.isEmpty()) {
            return false;
        }
        Set<String> requestIds = toolRequests.stream()
                .map(ToolExecutionRequest::id)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestIds.isEmpty()) {
            return resultMessages.size() >= toolRequests.size();
        }
        Set<String> resultIds = resultMessages.stream()
                .map(ToolExecutionResultMessage::id)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return requestIds.equals(resultIds);
    }

    private boolean isEmptyAiMessage(AiMessage aiMessage) {
        if (aiMessage == null) {
            return true;
        }
        if (aiMessage.hasToolExecutionRequests()) {
            return false;
        }
        return !StringUtils.hasText(aiMessage.text());
    }

    private String buildToolExchangeSummary(AiMessage aiMessage, List<ToolExecutionResultMessage> resultMessages) {
        List<String> lines = new ArrayList<>();
        if (StringUtils.hasText(aiMessage.text())) {
            lines.add("模型规划: " + compactText(aiMessage.text(), 180));
        }
        String plannedTools = summarizeToolRequests(aiMessage.toolExecutionRequests());
        if (StringUtils.hasText(plannedTools)) {
            lines.add("计划调用: " + plannedTools);
        }
        appendToolResultLines(lines, resultMessages);
        return buildToolSummary(lines);
    }

    private String buildOrphanToolSummary(List<ToolExecutionResultMessage> resultMessages) {
        List<String> lines = new ArrayList<>();
        if (resultMessages == null || resultMessages.isEmpty()) {
            return null;
        }
        lines.add("检测到历史工具结果与原始工具调用已失配，以下结果转为纯文本证据继续参与推理。");
        appendToolResultLines(lines, resultMessages);
        return buildToolSummary(lines);
    }

    private String summarizeToolRequests(List<ToolExecutionRequest> toolRequests) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return null;
        }
        return toolRequests.stream()
                .limit(4)
                .map(request -> {
                    String toolName = StringUtils.hasText(request.name()) ? request.name() : "unknown";
                    String arguments = compactText(request.arguments(), 80);
                    return StringUtils.hasText(arguments) ? toolName + "(" + arguments + ")" : toolName;
                })
                .collect(Collectors.joining("；"));
    }

    private void appendToolResultLines(List<String> lines, List<ToolExecutionResultMessage> resultMessages) {
        if (resultMessages == null || resultMessages.isEmpty()) {
            return;
        }
        for (ToolExecutionResultMessage resultMessage : resultMessages) {
            String text = compactText(resultMessage.text(), 220);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String toolName = StringUtils.hasText(resultMessage.toolName()) ? resultMessage.toolName() : "unknown";
            lines.add("工具[" + toolName + "] " + text);
            if (lines.size() >= 6) {
                break;
            }
        }
    }

    private String buildToolSummary(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        StringBuilder summary = new StringBuilder(TOOL_SUMMARY_HEADER).append("\n");
        for (String line : lines) {
            summary.append("- ").append(line).append("\n");
        }
        return compactText(summary.toString(), TOOL_SUMMARY_CHAR_LIMIT);
    }

    /**
     * 模型驱动摘要：使用小模型生成高质量压缩摘要
     */
    private String modelBasedSummarize(List<ChatMessage> historicalMessages) {
        if (compactionModel == null || historicalMessages == null || historicalMessages.isEmpty()) {
            return null;
        }
        try {
            StringBuilder historyText = new StringBuilder();
            for (ChatMessage msg : historicalMessages) {
                String role = msg instanceof UserMessage ? "用户" :
                        msg instanceof AiMessage ? "AI" : "系统";
                String text = msg instanceof UserMessage um ? um.singleText() :
                        msg instanceof AiMessage am ? am.text() : null;
                if (StringUtils.hasText(text)) {
                    historyText.append(role).append(": ").append(compactText(text, 300)).append("\n");
                }
            }
            if (historyText.isEmpty()) {
                return null;
            }
            ChatResponse response = compactionModel.chat(List.of(
                    SystemMessage.from(MODEL_SUMMARIZATION_PROMPT),
                    UserMessage.from(historyText.toString())
            ));
            String summary = response.aiMessage().text();
            if (StringUtils.hasText(summary)) {
                log.info("模型驱动上下文压缩完成: inputMessages={}, summaryChars={}",
                        historicalMessages.size(), summary.length());
                return SUMMARY_HEADER + "\n" + compactText(summary, SUMMARY_CHAR_LIMIT);
            }
        } catch (Exception e) {
            log.warn("模型驱动摘要失败，降级到字符串提取: {}", e.getMessage());
        }
        return null;
    }

    private String buildHistoricalSummary(List<ChatMessage> historicalMessages) {
        List<String> goals = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        List<String> references = new ArrayList<>();

        for (ChatMessage message : historicalMessages) {
            if (message instanceof UserMessage userMessage) {
                String text = compactText(userMessage.singleText(), 180);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (text.startsWith(SUMMARY_HEADER)) {
                    facts.add(compactText(text.replace(SUMMARY_HEADER, ""), 220));
                } else if (goals.size() < 2) {
                    goals.add(text);
                }
            } else if (message instanceof ToolExecutionResultMessage toolMessage) {
                String text = compactText(toolMessage.text(), 180);
                if (StringUtils.hasText(text) && references.size() < 4) {
                    references.add("工具[" + toolMessage.toolName() + "] " + text);
                }
            } else if (message instanceof AiMessage aiMessage) {
                String text = compactText(aiMessage.text(), 180);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (text.startsWith(SUMMARY_HEADER)) {
                    facts.add(compactText(text.replace(SUMMARY_HEADER, ""), 220));
                } else if (decisions.size() < 4) {
                    decisions.add(text);
                } else if (facts.size() < 4) {
                    facts.add(text);
                }
            }
        }

        StringBuilder summary = new StringBuilder(SUMMARY_HEADER).append("\n");
        appendSection(summary, "目标", goals, "延续当前用户任务并完成节点目标");
        appendSection(summary, "关键事实", facts, "已基于历史轮次收集到部分有效证据");
        appendSection(summary, "关键决定", decisions, "已形成阶段性方案，保留最近原文消息继续推理");
        appendSection(summary, "引用", references, "详细原始证据保留在最近消息窗口内");
        return compactText(summary.toString(), SUMMARY_CHAR_LIMIT);
    }

    private void appendSection(StringBuilder summary, String title, List<String> items, String defaultText) {
        summary.append(title).append(":\n");
        if (items.isEmpty()) {
            summary.append("- ").append(defaultText).append("\n");
            return;
        }
        for (String item : items) {
            summary.append("- ").append(item).append("\n");
        }
    }

    private String compactText(String text, int limit) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(limit, 1)) + "...";
    }

    record CompactionResult(boolean compacted,
                            int beforeTokens,
                            int afterTokens,
                            int summaryChars,
                            int beforeMessages,
                            int afterMessages) {

        static CompactionResult noop() {
            return new CompactionResult(false, 0, 0, 0, 0, 0);
        }
    }

    record NormalizationResult(boolean normalized,
                               int convertedSegments,
                               int droppedMessages,
                               int beforeMessages,
                               int afterMessages) {

        static NormalizationResult noop() {
            return new NormalizationResult(false, 0, 0, 0, 0);
        }
    }

    private record ToolExchangeSegment(List<ToolExecutionResultMessage> resultMessages,
                                       int nextIndex,
                                       boolean complete) {
    }
}
