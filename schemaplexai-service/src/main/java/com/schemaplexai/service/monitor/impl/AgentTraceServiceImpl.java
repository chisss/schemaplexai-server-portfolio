package com.schemaplexai.service.monitor.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentTraceSpanMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentTraceSpan;
import com.schemaplexai.model.vo.monitor.AgentTraceFailureCategoryVO;
import com.schemaplexai.model.vo.monitor.AgentTraceFailureSummaryVO;
import com.schemaplexai.model.vo.monitor.AgentTraceSpanVO;
import com.schemaplexai.model.vo.monitor.AgentTraceVO;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.monitor.TraceFailureClassification;
import com.schemaplexai.service.monitor.TraceFailureClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agent Trace 服务实现
 */
@Service
@RequiredArgsConstructor
public class AgentTraceServiceImpl implements AgentTraceService {

    private final AgentTraceSpanMapper agentTraceSpanMapper;
    private final AgentMapper agentMapper;
    private final TraceFailureClassifier traceFailureClassifier;

    @Override
    public void recordAgentSpan(String traceId,
                                String tenantId,
                                String agentId,
                                LocalDateTime startedAt,
                                long durationMs,
                                long inputTokens,
                                long outputTokens,
                                String status) {
        AgentTraceSpan span = agentTraceSpanMapper.selectOne(new LambdaQueryWrapper<AgentTraceSpan>()
                .eq(AgentTraceSpan::getTraceId, traceId)
                .eq(AgentTraceSpan::getSpanType, "AGENT")
                .last("limit 1"));
        if (span == null) {
            span = new AgentTraceSpan();
            span.setTenantId(tenantId);
            span.setTraceId(traceId);
            span.setSpanId(traceId);
            span.setParentSpanId(null);
            span.setAgentId(agentId);
            span.setSpanType("AGENT");
            span.setName("Agent Execution");
            span.setStartedAt(startedAt);
            span.setDurationMs(durationMs);
            span.setInputTokens(inputTokens);
            span.setOutputTokens(outputTokens);
            span.setStatus(status);
            applyFailureClassification(span, null);
            agentTraceSpanMapper.insert(span);
            return;
        }
        span.setStartedAt(startedAt);
        span.setDurationMs(durationMs);
        span.setInputTokens(inputTokens);
        span.setOutputTokens(outputTokens);
        span.setStatus(status);
        applyFailureClassification(span, span.getFailureReason());
        agentTraceSpanMapper.updateById(span);
    }

    @Override
    public void recordModelSpan(String traceId,
                                String tenantId,
                                String agentId,
                                String modelName,
                                LocalDateTime startedAt,
                                long durationMs,
                                long inputTokens,
                                long outputTokens,
                                BigDecimal cost,
                                String status) {
        AgentTraceSpan span = buildChildSpan(traceId, tenantId, agentId, "LLM", modelName, startedAt, durationMs, status);
        span.setInputTokens(inputTokens);
        span.setOutputTokens(outputTokens);
        span.setCost(cost);
        applyFailureClassification(span, null);
        agentTraceSpanMapper.insert(span);
    }

    @Override
    public void recordToolSpan(String traceId,
                               String tenantId,
                               String agentId,
                               String toolName,
                               LocalDateTime startedAt,
                               long durationMs,
                               String status) {
        AgentTraceSpan span = buildChildSpan(traceId, tenantId, agentId, "TOOL", toolName, startedAt, durationMs, status);
        applyFailureClassification(span, null);
        agentTraceSpanMapper.insert(span);
    }

    @Override
    public List<AgentTraceVO> listAgentTraces(String agentId) {
        List<AgentTraceSpan> spans = agentTraceSpanMapper.selectList(new LambdaQueryWrapper<AgentTraceSpan>()
                .eq(AgentTraceSpan::getSpanType, "AGENT")
                .eq(StringUtils.hasText(agentId), AgentTraceSpan::getAgentId, agentId)
                .orderByDesc(AgentTraceSpan::getCreatedAt)
                .last("limit 50"));
        List<String> agentIds = spans.stream()
                .map(AgentTraceSpan::getAgentId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        Map<String, Agent> agentMap = agentIds.isEmpty()
                ? Map.of()
                : agentMapper.selectBatchIds(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity(), (left, right) -> left));
        return spans.stream().map(item -> {
            AgentTraceVO vo = new AgentTraceVO();
            vo.setTraceId(item.getTraceId());
            vo.setAgentId(item.getAgentId());
            vo.setAgentName(agentMap.containsKey(item.getAgentId()) ? agentMap.get(item.getAgentId()).getName() : item.getAgentId());
            vo.setStatus(item.getStatus());
            vo.setDurationMs(item.getDurationMs());
            vo.setInputTokens(item.getInputTokens());
            vo.setOutputTokens(item.getOutputTokens());
            vo.setStartedAt(item.getStartedAt());
            vo.setCreatedAt(item.getCreatedAt());
            vo.setCost(sumTraceCost(item.getTraceId()));
            return vo;
        }).toList();
    }

    @Override
    public List<AgentTraceSpanVO> listTraceSpans(String traceId) {
        return agentTraceSpanMapper.selectList(new LambdaQueryWrapper<AgentTraceSpan>()
                        .eq(AgentTraceSpan::getTraceId, traceId)
                        .orderByAsc(AgentTraceSpan::getStartedAt)
                        .orderByAsc(AgentTraceSpan::getCreatedAt))
                .stream()
                .map(item -> {
                    AgentTraceSpanVO vo = new AgentTraceSpanVO();
                    vo.setTraceId(item.getTraceId());
                    vo.setSpanId(item.getSpanId());
                    vo.setParentSpanId(item.getParentSpanId());
                    vo.setAgentId(item.getAgentId());
                    vo.setSpanType(item.getSpanType());
                    vo.setName(item.getName());
                    vo.setDurationMs(item.getDurationMs());
                    vo.setInputTokens(item.getInputTokens());
                    vo.setOutputTokens(item.getOutputTokens());
                    vo.setCost(item.getCost());
                    vo.setStatus(item.getStatus());
                    TraceFailureClassification classification = traceFailureClassifier.inferExisting(
                            item.getSpanType(), item.getName(), item.getStatus(), item.getFailureCategory(),
                            item.getFailureReason(), item.getRecoverable());
                    vo.setFailureCategory(classification != null ? classification.category() : null);
                    vo.setFailureReason(classification != null ? classification.reason() : null);
                    vo.setRecoverable(classification != null ? classification.recoverable() : null);
                    vo.setStartedAt(item.getStartedAt());
                    vo.setCreatedAt(item.getCreatedAt());
                    return vo;
                })
                .toList();
    }

    @Override
    public AgentTraceFailureSummaryVO summarizeFailures(String traceId) {
        List<TraceFailureClassification> classifications = agentTraceSpanMapper.selectList(
                        new LambdaQueryWrapper<AgentTraceSpan>().eq(AgentTraceSpan::getTraceId, traceId))
                .stream()
                .map(item -> traceFailureClassifier.inferExisting(item.getSpanType(), item.getName(), item.getStatus(),
                        item.getFailureCategory(), item.getFailureReason(), item.getRecoverable()))
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<String, List<TraceFailureClassification>> grouped = classifications.stream()
                .collect(Collectors.groupingBy(TraceFailureClassification::category));
        List<AgentTraceFailureCategoryVO> categories = grouped.entrySet().stream()
                .map(entry -> {
                    AgentTraceFailureCategoryVO vo = new AgentTraceFailureCategoryVO();
                    vo.setCategory(entry.getKey());
                    vo.setCount((long) entry.getValue().size());
                    vo.setRecoverable(entry.getValue().stream().allMatch(TraceFailureClassification::recoverable));
                    vo.setSampleReason(entry.getValue().stream()
                            .map(TraceFailureClassification::reason)
                            .filter(StringUtils::hasText)
                            .findFirst()
                            .orElse(null));
                    return vo;
                })
                .toList();
        AgentTraceFailureSummaryVO summary = new AgentTraceFailureSummaryVO();
        summary.setTraceId(traceId);
        summary.setRecoverableCount(classifications.stream().filter(TraceFailureClassification::recoverable).count());
        summary.setBlockingCount(classifications.stream().filter(item -> !item.recoverable()).count());
        summary.setCategories(categories);
        return summary;
    }

    private AgentTraceSpan buildChildSpan(String traceId,
                                          String tenantId,
                                          String agentId,
                                          String spanType,
                                          String name,
                                          LocalDateTime startedAt,
                                          long durationMs,
                                          String status) {
        AgentTraceSpan span = new AgentTraceSpan();
        span.setTenantId(tenantId);
        span.setTraceId(traceId);
        span.setSpanId(UUID.randomUUID().toString());
        span.setParentSpanId(traceId);
        span.setAgentId(agentId);
        span.setSpanType(spanType);
        span.setName(name);
        span.setStartedAt(startedAt);
        span.setDurationMs(durationMs);
        span.setStatus(status);
        return span;
    }

    private void applyFailureClassification(AgentTraceSpan span, String reason) {
        TraceFailureClassification classification = traceFailureClassifier.classify(
                span.getSpanType(), span.getName(), span.getStatus(), reason);
        if (classification == null) {
            return;
        }
        span.setFailureCategory(classification.category());
        span.setFailureReason(classification.reason());
        span.setRecoverable(classification.recoverable());
    }

    private BigDecimal sumTraceCost(String traceId) {
        return agentTraceSpanMapper.selectList(new LambdaQueryWrapper<AgentTraceSpan>()
                        .eq(AgentTraceSpan::getTraceId, traceId))
                .stream()
                .map(item -> item.getCost() == null ? BigDecimal.ZERO : item.getCost())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
