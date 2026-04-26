package com.schemaplexai.service.monitor;

import com.schemaplexai.model.vo.monitor.AgentTraceSpanVO;
import com.schemaplexai.model.vo.monitor.AgentTraceFailureSummaryVO;
import com.schemaplexai.model.vo.monitor.AgentTraceVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Agent Trace 服务
 */
public interface AgentTraceService {

    void recordAgentSpan(String traceId,
                         String tenantId,
                         String agentId,
                         LocalDateTime startedAt,
                         long durationMs,
                         long inputTokens,
                         long outputTokens,
                         String status);

    void recordModelSpan(String traceId,
                         String tenantId,
                         String agentId,
                         String modelName,
                         LocalDateTime startedAt,
                         long durationMs,
                         long inputTokens,
                         long outputTokens,
                         BigDecimal cost,
                         String status);

    void recordToolSpan(String traceId,
                        String tenantId,
                        String agentId,
                        String toolName,
                        LocalDateTime startedAt,
                        long durationMs,
                        String status);

    List<AgentTraceVO> listAgentTraces(String agentId);

    List<AgentTraceSpanVO> listTraceSpans(String traceId);

    AgentTraceFailureSummaryVO summarizeFailures(String traceId);
}
