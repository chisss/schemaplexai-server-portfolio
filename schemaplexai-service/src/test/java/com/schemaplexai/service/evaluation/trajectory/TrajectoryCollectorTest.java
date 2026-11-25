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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrajectoryCollectorTest {

    private final AgentExecutionMapper executionMapper = mock(AgentExecutionMapper.class);
    private final ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
    private final AgentExecutionLogMapper logMapper = mock(AgentExecutionLogMapper.class);
    private final AgentTraceSpanMapper spanMapper = mock(AgentTraceSpanMapper.class);
    private final TrajectoryCollector collector =
            new TrajectoryCollector(executionMapper, chatMessageMapper, logMapper, spanMapper);

    @BeforeEach
    void setUp() {
        AgentExecution execution = new AgentExecution();
        execution.setId("exec-1");
        execution.setAgentId("agent-1");
        execution.setConversationId("conv-1");
        execution.setStatus("completed");
        execution.setTokenInput(1200L);
        execution.setTokenOutput(800L);
        execution.setStartedAt(LocalDateTime.now().minusSeconds(90));
        execution.setCompletedAt(LocalDateTime.now());
        when(executionMapper.selectById("exec-1")).thenReturn(execution);

        ChatMessageEntity userMsg = new ChatMessageEntity();
        userMsg.setMessageType("USER");
        userMsg.setTextContent("帮我查一下今天上海的天气");
        userMsg.setMessageIndex(0);

        ChatMessageEntity aiToolCall = new ChatMessageEntity();
        aiToolCall.setMessageType("AI");
        aiToolCall.setTextContent("我需要调用天气查询工具");
        aiToolCall.setToolCalls(List.of(Map.of("name", "weather_query",
                "arguments", Map.of("city", "上海"))));
        aiToolCall.setMessageIndex(1);

        ChatMessageEntity toolResult = new ChatMessageEntity();
        toolResult.setMessageType("TOOL_EXECUTION_RESULT");
        toolResult.setTextContent("{\"city\":\"上海\",\"temp\":18,\"condition\":\"晴\"}");
        toolResult.setToolName("weather_query");
        toolResult.setToolCallId("call-1");
        toolResult.setMessageIndex(2);

        ChatMessageEntity aiAnswer = new ChatMessageEntity();
        aiAnswer.setMessageType("AI");
        aiAnswer.setTextContent("今天上海晴，气温 18 度。");
        aiAnswer.setMessageIndex(3);

        when(chatMessageMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(userMsg, aiToolCall, toolResult, aiAnswer));

        AgentExecutionLog roundStart = new AgentExecutionLog();
        roundStart.setLogType("ROUND_START");
        roundStart.setRoundNum(1);
        roundStart.setContent("第 1 轮开始");
        roundStart.setElapsedMs(0);

        AgentExecutionLog toolCallLimited = new AgentExecutionLog();
        toolCallLimited.setLogType("TOOL_CALL_LIMITED");
        toolCallLimited.setRoundNum(2);
        toolCallLimited.setToolName("weather_query");
        toolCallLimited.setContent("工具调用超限");
        toolCallLimited.setElapsedMs(45_000);

        when(logMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(roundStart, toolCallLimited));

        AgentTraceSpan agentSpan = new AgentTraceSpan();
        agentSpan.setTraceId("exec-1");
        agentSpan.setSpanType("AGENT");
        agentSpan.setStatus("COMPLETED");
        agentSpan.setCost(new BigDecimal("0.12"));

        AgentTraceSpan failedToolSpan = new AgentTraceSpan();
        failedToolSpan.setTraceId("exec-1");
        failedToolSpan.setSpanType("TOOL");
        failedToolSpan.setStatus("FAILED");
        failedToolSpan.setFailureCategory("TOOL_FAILURE");
        failedToolSpan.setRecoverable(true);
        failedToolSpan.setCost(new BigDecimal("0.01"));

        when(spanMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(agentSpan, failedToolSpan));
    }

    @Test
    void shouldCollectFullTrajectoryFromThreeLayers() {
        TrajectoryData trajectory = collector.collect("exec-1");

        assertThat(trajectory).isNotNull();
        assertThat(trajectory.getExecutionId()).isEqualTo("exec-1");
        assertThat(trajectory.getConversationId()).isEqualTo("conv-1");
        assertThat(trajectory.getAgentId()).isEqualTo("agent-1");
        assertThat(trajectory.getExecutionStatus()).isEqualTo("completed");
        assertThat(trajectory.getRounds()).isEqualTo(2);
        assertThat(trajectory.getTokenInput()).isEqualTo(1200L);
        assertThat(trajectory.getTokenOutput()).isEqualTo(800L);
        assertThat(trajectory.getDurationMs()).isGreaterThanOrEqualTo(90_000L - 5_000);
        assertThat(trajectory.getCost()).isEqualByComparingTo("0.13");

        assertThat(trajectory.getMessages()).hasSize(4);
        assertThat(trajectory.getMessages().get(1).getToolCallsJson()).contains("weather_query");
        assertThat(trajectory.getMessages().get(2).getToolName()).isEqualTo("weather_query");

        assertThat(trajectory.getEvents()).hasSize(2);
        assertThat(trajectory.getEvents().get(1).getLogType()).isEqualTo("TOOL_CALL_LIMITED");

        assertThat(trajectory.getSpanSummary().getTotal()).isEqualTo(2);
        assertThat(trajectory.getSpanSummary().getFailed()).isEqualTo(1);
        assertThat(trajectory.getSpanSummary().getRecoverable()).isEqualTo(1);
        assertThat(trajectory.getSpanSummary().getFailureCategories()).containsExactly("TOOL_FAILURE");
    }

    @Test
    void shouldReturnNullWhenExecutionMissing() {
        when(executionMapper.selectById("missing")).thenReturn(null);
        assertThat(collector.collect("missing")).isNull();
    }

    @Test
    void shouldRenderJudgeTextWithToolCallsAndEvents() {
        TrajectoryData trajectory = collector.collect("exec-1");
        String text = collector.renderForJudge(trajectory);

        assertThat(text).contains("【执行概览】");
        assertThat(text).contains("状态=completed");
        assertThat(text).contains("【对话轨迹】");
        assertThat(text).contains("USER: 帮我查一下今天上海的天气");
        assertThat(text).contains("tool_calls=");
        assertThat(text).contains("TOOL_EXECUTION_RESULT: tool=weather_query");
        assertThat(text).contains("【关键执行事件】");
        assertThat(text).contains("TOOL_CALL_LIMITED tool=weather_query");
        assertThat(text).contains("【Span统计】").contains("失败=1").contains("TOOL_FAILURE");
    }

    @Test
    void shouldTruncateLongJudgeTextToBudget() {
        ChatMessageEntity huge = new ChatMessageEntity();
        huge.setMessageType("AI");
        huge.setTextContent("x".repeat(20_000));
        huge.setMessageIndex(9);
        when(chatMessageMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(huge));

        TrajectoryData trajectory = collector.collect("exec-1");
        String text = collector.renderForJudge(trajectory);

        assertThat(text.length()).isLessThanOrEqualTo(12_000 + 50);
        assertThat(text).contains("截断");
    }
}
