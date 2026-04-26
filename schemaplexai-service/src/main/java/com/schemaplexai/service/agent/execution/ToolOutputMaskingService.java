package com.schemaplexai.service.agent.execution;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 工具输出动态遮蔽服务（参照 Gemini CLI ToolOutputMaskingService）
 *
 * <p>当 ChatMemory 中历史工具输出占用过多 Token 时，将旧轮次的工具结果压缩为摘要，
 * 保留消息结构但减少内容长度，避免丢弃消息（与 AgentChatMemoryCompactor 的截断策略互补）。</p>
 *
 * <p>策略：Hybrid Backward Scanned FIFO
 * <ul>
 *   <li>从最旧的工具结果消息开始向后扫描</li>
 *   <li>最近 {@code protectRecentRounds} 轮的工具结果不压缩</li>
 *   <li>已压缩的消息不重复压缩</li>
 *   <li>压缩后保留前 200 字符 + 压缩标记</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
public class ToolOutputMaskingService {

    /** 压缩后保留的前缀字符数 */
    private static final int MASKED_PREVIEW_CHARS = 200;
    /** 压缩标记前缀 */
    private static final String MASKED_MARKER = "[已压缩，原始长度: ";
    /** 压缩标记后缀 */
    private static final String MASKED_SUFFIX = " 字符]";
    /** 触发遮蔽的工具结果最小字符数（小于此值不压缩） */
    private static final int MIN_CHARS_TO_MASK = 500;
    /** 触发遮蔽的 ChatMemory 总字符数阈值 */
    private static final int TOTAL_CHARS_THRESHOLD = 40_000;

    /**
     * 检查是否需要执行工具输出遮蔽
     */
    public boolean shouldMask(ChatMemory memory) {
        if (memory == null) return false;
        List<ChatMessage> messages = memory.messages();
        if (messages == null || messages.isEmpty()) return false;
        int totalChars = messages.stream()
                .mapToInt(m -> m.toString().length())
                .sum();
        return totalChars > TOTAL_CHARS_THRESHOLD;
    }

    /**
     * 执行工具输出遮蔽：压缩旧轮次的大型工具结果消息
     *
     * @param memory              ChatMemory
     * @param protectRecentRounds 保护最近 N 轮（以 AiMessage 为轮次边界）的工具结果不压缩
     * @return 遮蔽结果统计
     */
    public MaskingResult maskOldToolOutputs(ChatMemory memory, int protectRecentRounds) {
        if (memory == null) return MaskingResult.noop();
        List<ChatMessage> messages = new ArrayList<>(memory.messages());
        if (messages.isEmpty()) return MaskingResult.noop();

        // 按 AiMessage 划分轮次边界，找出最近 N 轮内的工具结果索引（受保护）
        Set<Integer> protectedIndices = findProtectedToolResultIndices(messages, Math.max(0, protectRecentRounds));

        int maskedCount = 0;
        int savedChars  = 0;

        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolExecutionResultMessage original)) continue;
            if (protectedIndices.contains(i)) continue;
            String text = original.text();
            if (!StringUtils.hasText(text)) continue;
            if (text.contains(MASKED_MARKER)) continue;
            if (text.length() < MIN_CHARS_TO_MASK) continue;

            String masked = buildMaskedText(text);
            int saved = text.length() - masked.length();
            if (saved <= 0) continue;

            ToolExecutionResultMessage replacement = ToolExecutionResultMessage.from(
                    original.id(), original.toolName(), masked);
            messages.set(i, replacement);
            maskedCount++;
            savedChars += saved;
        }

        if (maskedCount > 0) {
            memory.set(messages);
            log.debug("[工具输出遮蔽] 压缩 {} 条工具结果，节省 {} 字符", maskedCount, savedChars);
        }

        return new MaskingResult(maskedCount, savedChars);
    }

    /**
     * 从消息列表末尾向前扫描，找出最近 N 轮（以 AiMessage 为轮次边界）内的所有 ToolExecutionResultMessage 索引
     */
    private Set<Integer> findProtectedToolResultIndices(List<ChatMessage> messages, int protectRounds) {
        Set<Integer> protectedIndices = new HashSet<>();
        int roundsSeen = 0;
        for (int i = messages.size() - 1; i >= 0 && roundsSeen < protectRounds; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage) {
                roundsSeen++;
            }
            if (msg instanceof ToolExecutionResultMessage && roundsSeen < protectRounds) {
                protectedIndices.add(i);
            }
        }
        return protectedIndices;
    }

    private String buildMaskedText(String original) {
        int previewLen = Math.min(MASKED_PREVIEW_CHARS, original.length());
        String preview = original.substring(0, previewLen);
        return preview + "\n" + MASKED_MARKER + original.length() + MASKED_SUFFIX;
    }

    public record MaskingResult(int maskedCount, int savedChars) {
        public static MaskingResult noop() {
            return new MaskingResult(0, 0);
        }
        public boolean applied() {
            return maskedCount > 0;
        }
    }
}
