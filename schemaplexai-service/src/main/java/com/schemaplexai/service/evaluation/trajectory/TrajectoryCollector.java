package com.schemaplexai.service.evaluation.trajectory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentExecutionLogMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentTraceSpanMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AgentExecutionLog;
import com.schemaplexai.model.entity.AgentTraceSpan;
import com.schemaplexai.model.entity.ChatMessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Agent 执行轨迹采集器
 * <p>
 * 按 executionId 从三层数据组装结构化轨迹：
 * <ul>
 *   <li>sf_chat_message：用户问题 + AI 每轮响应 + 工具入参（toolCalls）+ 工具返回（TOOL_EXECUTION_RESULT）</li>
 *   <li>sf_agent_execution_log：关键执行事件（轮次/循环告警/调用超限/降级收敛等边界容错素材）</li>
 *   <li>sf_agent_trace_span：span 树（traceId = executionId）——成本/耗时/失败归因</li>
 * </ul>
 * 同时负责把结构化轨迹渲染为 Judge 可读文本（{@link #renderForJudge}）。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrajectoryCollector {

    /** Judge 输入文本总长上限（与质量中心交叉审查口径一致） */
    private static final int JUDGE_TEXT_MAX_CHARS = 12_000;

    /** 单条消息渲染截断长度 */
    private static final int SINGLE_MESSAGE_MAX_CHARS = 1_500;

    /** 事件渲染条数上限（事件多时只保留高价值类型） */
    private static final int EVENT_RENDER_MAX = 40;

    private final AgentExecutionMapper agentExecutionMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final AgentExecutionLogMapper agentExecutionLogMapper;
    private final AgentTraceSpanMapper agentTraceSpanMapper;

    /**
     * 采集完整轨迹；执行记录不存在时返回 null
     */
    public TrajectoryData collect(String executionId) {
        if (!StringUtils.hasText(executionId)) {
            return null;
        }
        AgentExecution execution = agentExecutionMapper.selectById(executionId);
        if (execution == null) {
            log.warn("轨迹采集失败: 执行记录不存在 executionId={}", executionId);
            return null;
        }
        List<TrajectoryData.TrajectoryMessage> messages = loadMessages(execution);
        List<TrajectoryData.TrajectoryEvent> events = loadEvents(executionId);
        TrajectoryData.SpanSummary spanSummary = loadSpanSummary(executionId);

        Long durationMs = resolveDurationMs(execution);
        BigDecimal cost = spanSummary != null && spanSummary.getCost() != null
                ? spanSummary.getCost() : BigDecimal.ZERO;
        return TrajectoryData.builder()
                .executionId(executionId)
                .conversationId(execution.getConversationId())
                .agentId(execution.getAgentId())
                .executionStatus(execution.getStatus())
                .rounds(resolveRounds(events))
                .tokenInput(execution.getTokenInput())
                .tokenOutput(execution.getTokenOutput())
                .durationMs(durationMs)
                .cost(cost)
                .messages(messages)
                .events(events)
                .spanSummary(spanSummary)
                .build();
    }

    /**
     * 把结构化轨迹渲染为 Judge 评审文本（截断保护 + 关键事件优先）
     */
    public String renderForJudge(TrajectoryData trajectory) {
        if (trajectory == null) {
            return "（无轨迹数据）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【执行概览】")
                .append(" 状态=").append(nullToDash(trajectory.getExecutionStatus()))
                .append(" 轮次=").append(nullToDash(trajectory.getRounds()))
                .append(" 耗时ms=").append(nullToDash(trajectory.getDurationMs()))
                .append(" 输入Token=").append(nullToDash(trajectory.getTokenInput()))
                .append(" 输出Token=").append(nullToDash(trajectory.getTokenOutput()))
                .append(" 成本=").append(trajectory.getCost() == null ? "-" : trajectory.getCost().toPlainString())
                .append("\n");
        if (trajectory.getSpanSummary() != null) {
            TrajectoryData.SpanSummary span = trajectory.getSpanSummary();
            sb.append("【Span统计】 总数=").append(span.getTotal())
                    .append(" 失败=").append(span.getFailed())
                    .append(" 可恢复=").append(span.getRecoverable())
                    .append(" 失败归因=").append(span.getFailureCategories() == null || span.getFailureCategories().isEmpty()
                            ? "-" : String.join(",", span.getFailureCategories()))
                    .append("\n");
        }
        sb.append("【对话轨迹】\n");
        appendMessages(sb, trajectory.getMessages());
        sb.append("【关键执行事件】\n");
        appendEvents(sb, trajectory.getEvents());
        return trimToBudget(sb.toString());
    }

    // ------------------------------------------------------------------
    //  采集内部
    // ------------------------------------------------------------------

    private List<TrajectoryData.TrajectoryMessage> loadMessages(AgentExecution execution) {
        String executionId = execution.getId();
        String conversationId = execution.getConversationId();
        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        // 消息 owner id = conversationId（见 AgentChatMemoryCompactor）；executionId 条件兼容直连会话场景
        wrapper.and(w -> {
            if (StringUtils.hasText(conversationId)) {
                w.eq(ChatMessageEntity::getConversationId, conversationId);
            }
            if (StringUtils.hasText(executionId)) {
                if (StringUtils.hasText(conversationId)) {
                    w.or();
                }
                w.eq(ChatMessageEntity::getExecutionId, executionId);
            }
        });
        wrapper.orderByAsc(ChatMessageEntity::getMessageIndex).orderByAsc(ChatMessageEntity::getCreatedAt);
        return chatMessageMapper.selectList(wrapper).stream()
                .filter(Objects::nonNull)
                .map(this::toTrajectoryMessage)
                .toList();
    }

    private TrajectoryData.TrajectoryMessage toTrajectoryMessage(ChatMessageEntity entity) {
        String toolCallsJson = null;
        if (entity.getToolCalls() != null && !entity.getToolCalls().isEmpty()) {
            try {
                toolCallsJson = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                        .build().writeValueAsString(entity.getToolCalls());
            } catch (Exception e) {
                toolCallsJson = String.valueOf(entity.getToolCalls());
            }
        }
        return TrajectoryData.TrajectoryMessage.builder()
                .messageType(entity.getMessageType())
                .text(entity.getTextContent())
                .toolName(entity.getToolName())
                .toolCallId(entity.getToolCallId())
                .toolCallsJson(toolCallsJson)
                .messageIndex(entity.getMessageIndex())
                .turnIndex(entity.getTurnIndex())
                .build();
    }

    private List<TrajectoryData.TrajectoryEvent> loadEvents(String executionId) {
        return agentExecutionLogMapper.selectList(new LambdaQueryWrapper<AgentExecutionLog>()
                        .eq(AgentExecutionLog::getExecutionId, executionId)
                        .orderByAsc(AgentExecutionLog::getCreatedAt))
                .stream()
                .map(entity -> TrajectoryData.TrajectoryEvent.builder()
                        .logType(entity.getLogType())
                        .roundNum(entity.getRoundNum())
                        .toolName(entity.getToolName())
                        .content(entity.getContent())
                        .elapsedMs(entity.getElapsedMs())
                        .build())
                .toList();
    }

    private TrajectoryData.SpanSummary loadSpanSummary(String executionId) {
        List<AgentTraceSpan> spans = agentTraceSpanMapper.selectList(new LambdaQueryWrapper<AgentTraceSpan>()
                .eq(AgentTraceSpan::getTraceId, executionId));
        if (spans.isEmpty()) {
            return TrajectoryData.SpanSummary.builder().total(0).failed(0).recoverable(0)
                    .failureCategories(List.of()).cost(BigDecimal.ZERO).build();
        }
        int failed = 0;
        int recoverable = 0;
        BigDecimal cost = BigDecimal.ZERO;
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        for (AgentTraceSpan span : spans) {
            boolean isFailed = span.getStatus() != null
                    && ("FAILED".equalsIgnoreCase(span.getStatus()) || "ERROR".equalsIgnoreCase(span.getStatus()));
            if (isFailed) {
                failed++;
                if (Boolean.TRUE.equals(span.getRecoverable())) {
                    recoverable++;
                }
            }
            if (StringUtils.hasText(span.getFailureCategory())) {
                categories.add(span.getFailureCategory());
            }
            if (span.getCost() != null) {
                cost = cost.add(span.getCost());
            }
        }
        return TrajectoryData.SpanSummary.builder()
                .total(spans.size())
                .failed(failed)
                .recoverable(recoverable)
                .failureCategories(new ArrayList<>(categories))
                .cost(cost)
                .build();
    }

    private Integer resolveRounds(List<TrajectoryData.TrajectoryEvent> events) {
        return events.stream()
                .map(TrajectoryData.TrajectoryEvent::getRoundNum)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(null);
    }

    private Long resolveDurationMs(AgentExecution execution) {
        LocalDateTime start = execution.getStartedAt() != null ? execution.getStartedAt() : execution.getCreatedAt();
        LocalDateTime end = execution.getCompletedAt() != null ? execution.getCompletedAt() : LocalDateTime.now();
        if (start == null || end == null) {
            return null;
        }
        return Math.max(0, Duration.between(start, end).toMillis());
    }

    // ------------------------------------------------------------------
    //  渲染内部
    // ------------------------------------------------------------------

    private void appendMessages(StringBuilder sb, List<TrajectoryData.TrajectoryMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            sb.append("（无对话消息）\n");
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            TrajectoryData.TrajectoryMessage message = messages.get(i);
            String type = nullToDash(message.getMessageType());
            String line = switch (type) {
                case "TOOL_EXECUTION_RESULT" -> String.format("[%d] %s: %s", i + 1, type,
                        "tool=" + nullToDash(message.getToolName()) + " result=" + abbreviate(message.getText()));
                case "AI" -> String.format("[%d] %s: %s%s", i + 1, type, abbreviate(message.getText()),
                        StringUtils.hasText(message.getToolCallsJson())
                                ? " tool_calls=" + abbreviate(message.getToolCallsJson()) : "");
                default -> String.format("[%d] %s: %s", i + 1, type, abbreviate(message.getText()));
            };
            sb.append(line).append("\n");
        }
    }

    private void appendEvents(StringBuilder sb, List<TrajectoryData.TrajectoryEvent> events) {
        if (events == null || events.isEmpty()) {
            sb.append("（无关键事件）\n");
            return;
        }
        int rendered = 0;
        for (TrajectoryData.TrajectoryEvent event : events) {
            if (rendered >= EVENT_RENDER_MAX) {
                sb.append("...（其余 ").append(events.size() - rendered).append(" 条事件已省略）\n");
                break;
            }
            sb.append("[round=").append(nullToDash(event.getRoundNum()))
                    .append("] ").append(nullToDash(event.getLogType()));
            if (StringUtils.hasText(event.getToolName())) {
                sb.append(" tool=").append(event.getToolName());
            }
            if (StringUtils.hasText(event.getContent())) {
                sb.append(": ").append(abbreviate(event.getContent()));
            }
            sb.append("\n");
            rendered++;
        }
    }

    private String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "-";
        }
        String cleaned = text.replaceAll("\\s+", " ").trim();
        if (cleaned.length() <= SINGLE_MESSAGE_MAX_CHARS) {
            return cleaned;
        }
        return cleaned.substring(0, SINGLE_MESSAGE_MAX_CHARS) + "…(截断)";
    }

    private String trimToBudget(String text) {
        if (text.length() <= JUDGE_TEXT_MAX_CHARS) {
            return text;
        }
        return text.substring(0, JUDGE_TEXT_MAX_CHARS) + "\n…(评审文本超长已截断)";
    }

    private String nullToDash(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }
}
