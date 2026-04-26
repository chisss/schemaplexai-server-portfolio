package com.schemaplexai.service.agent.execution;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Agentic Loop 循环检测服务（参照 Gemini CLI loopDetectionService 三层检测策略）
 *
 * <p>检测层级：
 * <ol>
 *   <li>哈希检测（快速）：对 AI 响应文本计算 SHA-256，滑动窗口内重复即告警</li>
 *   <li>工具序列检测（中等）：连续多轮工具调用序列完全相同即告警</li>
 *   <li>综合判断：两层均告警时确认为循环，触发强制收敛</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
public class AgentLoopDetectionService {

    /** 哈希滑动窗口大小 */
    private static final int HASH_WINDOW_SIZE = 5;
    /** 触发哈希循环告警的连续重复次数 */
    private static final int HASH_REPEAT_THRESHOLD = 2;
    /** 触发工具序列循环告警的连续重复次数 */
    private static final int TOOL_SEQ_REPEAT_THRESHOLD = 3;

    /**
     * 综合检测：在每轮 AI 响应后调用，返回检测结果
     */
    public LoopDetectionResult check(AgentLoopState state, String content,
                                      List<ToolExecutionRequest> toolRequests) {
        boolean hashLoop   = detectByHash(state, content);
        boolean toolLoop   = detectByToolSequence(state, toolRequests);

        if (hashLoop && toolLoop) {
            log.warn("[循环检测] 哈希+工具序列双重告警，确认为循环，触发强制收敛");
            return LoopDetectionResult.CONFIRMED_LOOP;
        }
        if (hashLoop || toolLoop) {
            log.debug("[循环检测] 单层告警 hash={} toolSeq={}", hashLoop, toolLoop);
            return LoopDetectionResult.WARNING;
        }
        return LoopDetectionResult.NORMAL;
    }

    /**
     * 第一层：哈希检测
     * 将当前响应哈希加入滑动窗口，若窗口内出现 HASH_REPEAT_THRESHOLD 次相同哈希则告警
     */
    boolean detectByHash(AgentLoopState state, String content) {
        if (!StringUtils.hasText(content)) return false;
        String hash = sha256(content);
        state.getRecentResponseHashes().addLast(hash);
        if (state.getRecentResponseHashes().size() > HASH_WINDOW_SIZE) {
            state.getRecentResponseHashes().pollFirst();
        }
        long count = state.getRecentResponseHashes().stream().filter(hash::equals).count();
        return count >= HASH_REPEAT_THRESHOLD;
    }

    /**
     * 第二层：工具调用序列检测
     * 将当前轮次工具序列摘要加入队列，若连续 TOOL_SEQ_REPEAT_THRESHOLD 轮完全相同则告警
     */
    boolean detectByToolSequence(AgentLoopState state, List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) return false;
        String seq = buildToolSequenceKey(requests);
        state.getRecentToolSequences().addLast(seq);
        if (state.getRecentToolSequences().size() > TOOL_SEQ_REPEAT_THRESHOLD) {
            state.getRecentToolSequences().pollFirst();
        }
        if (state.getRecentToolSequences().size() < TOOL_SEQ_REPEAT_THRESHOLD) return false;
        return state.getRecentToolSequences().stream().allMatch(seq::equals);
    }

    /** 将工具调用列表序列化为可比较的 key（toolName:argHash 拼接） */
    private String buildToolSequenceKey(List<ToolExecutionRequest> requests) {
        return requests.stream()
                .map(r -> r.name() + ":" + sha256Short(r.arguments()))
                .collect(Collectors.joining("|"));
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }

    private String sha256Short(String input) {
        if (!StringUtils.hasText(input)) return "empty";
        return sha256(input).substring(0, 8);
    }

    public enum LoopDetectionResult {
        NORMAL,
        WARNING,
        CONFIRMED_LOOP
    }
}
