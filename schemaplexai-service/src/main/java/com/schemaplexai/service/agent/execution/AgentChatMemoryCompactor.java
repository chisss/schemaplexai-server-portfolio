package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMemory 压缩器。
 *
 * <p>策略：
 * <ul>
 *   <li>底层窗口切换为 TokenWindow</li>
 *   <li>保留最近原文消息</li>
 *   <li>将更早轮次折叠为结构化历史摘要</li>
 * </ul>
 */
final class AgentChatMemoryCompactor {

    private static final String SUMMARY_HEADER = "[历史摘要]";
    private static final int MIN_SUMMARIZE_MESSAGE_COUNT = 8;
    private static final int SUMMARY_CHAR_LIMIT = 1_500;
    private static final int LARGE_TOOL_RESULT_CHAR_THRESHOLD = 1_200;

    private final TokenEstimatorSupport tokenEstimatorSupport;

    AgentChatMemoryCompactor(TokenEstimatorSupport tokenEstimatorSupport) {
        this.tokenEstimatorSupport = tokenEstimatorSupport;
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
        String summary = buildHistoricalSummary(historical);
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
}
