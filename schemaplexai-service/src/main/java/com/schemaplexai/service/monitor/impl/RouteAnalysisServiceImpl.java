package com.schemaplexai.service.monitor.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.RoutingDecisionLogMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.RoutingDecisionLog;
import com.schemaplexai.model.vo.system.ModelRuntimeMetricVO;
import com.schemaplexai.model.vo.system.RouteAnalysisModelShareVO;
import com.schemaplexai.model.vo.system.RouteAnalysisTrendPointVO;
import com.schemaplexai.model.vo.system.RouteAnalysisVO;
import com.schemaplexai.model.vo.system.RoutingDecisionLogVO;
import com.schemaplexai.service.ai.ModelMetricsTracker;
import com.schemaplexai.service.monitor.RouteAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由分析服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteAnalysisServiceImpl implements RouteAnalysisService {

    private final RoutingDecisionLogMapper routingDecisionLogMapper;
    private final AiModelMapper aiModelMapper;
    private final AgentMapper agentMapper;
    private final ModelMetricsTracker modelMetricsTracker;

    @Override
    public void recordDecision(String tenantId,
                               String agentId,
                               String requestId,
                               String selectedModelId,
                               String strategy,
                               List<String> candidateModels,
                               String reason,
                               long latencyMs,
                               long tokenCount,
                               BigDecimal cost) {
        RoutingDecisionLog logEntity = new RoutingDecisionLog();
        logEntity.setTenantId(tenantId);
        logEntity.setAgentId(agentId);
        logEntity.setRequestId(requestId);
        logEntity.setSelectedModelId(selectedModelId);
        logEntity.setStrategy(strategy);
        logEntity.setCandidateModels(candidateModels);
        logEntity.setReason(reason);
        logEntity.setLatencyMs(latencyMs);
        logEntity.setTokenCount(tokenCount);
        logEntity.setCost(cost);
        routingDecisionLogMapper.insert(logEntity);
    }

    @Override
    public RouteAnalysisVO getAnalysis() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        List<RoutingDecisionLog> logs = routingDecisionLogMapper.selectList(new LambdaQueryWrapper<RoutingDecisionLog>()
                .eq(StringUtils.hasText(tenantId), RoutingDecisionLog::getTenantId, tenantId)
                .ge(RoutingDecisionLog::getCreatedAt, LocalDateTime.now().minusDays(7))
                .orderByDesc(RoutingDecisionLog::getCreatedAt)
                .last("limit 300"));
        List<String> modelIds = logs.stream()
                .map(RoutingDecisionLog::getSelectedModelId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        Map<String, AiModel> modelMap = new LinkedHashMap<>();
        aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                        .eq(StringUtils.hasText(tenantId), AiModel::getTenantId, tenantId)
                        .eq(AiModel::getStatus, "active")
                        .orderByAsc(AiModel::getCreatedAt))
                .forEach(model -> modelMap.put(model.getId(), model));
        if (!CollectionUtils.isEmpty(modelIds)) {
            aiModelMapper.selectBatchIds(modelIds).forEach(model -> {
                if (model != null) {
                    modelMap.putIfAbsent(model.getId(), model);
                }
            });
        }
        List<String> agentIds = logs.stream()
                .map(RoutingDecisionLog::getAgentId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        Map<String, Agent> agentMap = CollectionUtils.isEmpty(agentIds)
                ? Map.of()
                : agentMapper.selectBatchIds(agentIds).stream()
                .collect(Collectors.toMap(Agent::getId, Function.identity(), (left, right) -> left));

        RouteAnalysisVO vo = new RouteAnalysisVO();
        vo.setRealtimeMetrics(buildRealtimeMetrics(modelMap));
        vo.setSelectedDistribution(buildDistribution(logs, modelMap));
        vo.setTrend(buildTrend(logs, modelMap));
        vo.setRecentLogs(buildRecentLogs(logs.stream().limit(50).toList(), modelMap, agentMap));
        return vo;
    }

    private List<ModelRuntimeMetricVO> buildRealtimeMetrics(Map<String, AiModel> modelMap) {
        if (modelMap.isEmpty()) {
            return List.of();
        }
        return modelMap.values().stream().map(model -> {
            ModelMetricsTracker.RuntimeMetrics metrics = modelMetricsTracker.loadMetrics(model.getId());
            ModelRuntimeMetricVO vo = new ModelRuntimeMetricVO();
            vo.setModelId(model.getId());
            vo.setModelName(model.getName());
            vo.setRequestCount1m(metrics.requestCount1m());
            vo.setErrorRate1m(metrics.errorRate1m());
            vo.setP95LatencyMs(metrics.p95LatencyMs());
            vo.setHealthStatus(metrics.healthStatus());
            vo.setLastUpdated(metrics.lastUpdated());
            return vo;
        }).toList();
    }

    private List<RouteAnalysisModelShareVO> buildDistribution(List<RoutingDecisionLog> logs, Map<String, AiModel> modelMap) {
        if (CollectionUtils.isEmpty(logs)) {
            return List.of();
        }
        Map<String, Long> countMap = logs.stream()
                .filter(item -> StringUtils.hasText(item.getSelectedModelId()))
                .collect(Collectors.groupingBy(RoutingDecisionLog::getSelectedModelId, LinkedHashMap::new, Collectors.counting()));
        long total = countMap.values().stream().mapToLong(Long::longValue).sum();
        List<RouteAnalysisModelShareVO> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : countMap.entrySet()) {
            RouteAnalysisModelShareVO vo = new RouteAnalysisModelShareVO();
            vo.setModelId(entry.getKey());
            vo.setModelName(resolveModelName(entry.getKey(), modelMap));
            vo.setRequestCount(entry.getValue());
            vo.setTrafficShare(total == 0 ? 0D : entry.getValue() * 100.0 / total);
            result.add(vo);
        }
        return result;
    }

    private List<RouteAnalysisTrendPointVO> buildTrend(List<RoutingDecisionLog> logs, Map<String, AiModel> modelMap) {
        if (CollectionUtils.isEmpty(logs)) {
            return List.of();
        }
        Map<String, TrendAggregation> grouped = new LinkedHashMap<>();
        for (RoutingDecisionLog log : logs) {
            LocalDateTime bucket = (log.getCreatedAt() == null ? LocalDateTime.now() : log.getCreatedAt()).truncatedTo(ChronoUnit.HOURS);
            String key = bucket + "|" + log.getSelectedModelId();
            grouped.computeIfAbsent(key, ignored -> new TrendAggregation(bucket, log.getSelectedModelId()))
                    .add(log.getLatencyMs(), log.getCost());
        }
        return grouped.values().stream().map(item -> {
            RouteAnalysisTrendPointVO vo = new RouteAnalysisTrendPointVO();
            vo.setBucketTime(item.bucketTime);
            vo.setModelId(item.modelId);
            vo.setModelName(resolveModelName(item.modelId, modelMap));
            vo.setAvgLatencyMs(item.requestCount == 0 ? 0D : item.totalLatency * 1.0 / item.requestCount);
            vo.setAvgCost(item.requestCount == 0 ? BigDecimal.ZERO : item.totalCost.divide(BigDecimal.valueOf(item.requestCount), 6, RoundingMode.HALF_UP));
            vo.setRequestCount(item.requestCount);
            return vo;
        }).toList();
    }

    private List<RoutingDecisionLogVO> buildRecentLogs(List<RoutingDecisionLog> logs,
                                                       Map<String, AiModel> modelMap,
                                                       Map<String, Agent> agentMap) {
        return logs.stream().map(item -> {
            RoutingDecisionLogVO vo = new RoutingDecisionLogVO();
            vo.setId(item.getId());
            vo.setAgentId(item.getAgentId());
            vo.setAgentName(agentMap.containsKey(item.getAgentId()) ? agentMap.get(item.getAgentId()).getName() : item.getAgentId());
            vo.setRequestId(item.getRequestId());
            vo.setSelectedModelId(item.getSelectedModelId());
            vo.setSelectedModelName(resolveModelName(item.getSelectedModelId(), modelMap));
            vo.setStrategy(item.getStrategy());
            vo.setCandidateModels(item.getCandidateModels());
            vo.setReason(item.getReason());
            vo.setLatencyMs(item.getLatencyMs());
            vo.setTokenCount(item.getTokenCount());
            vo.setCost(item.getCost());
            vo.setCreatedAt(item.getCreatedAt());
            return vo;
        }).toList();
    }

    private String resolveModelName(String modelId, Map<String, AiModel> modelMap) {
        if (!StringUtils.hasText(modelId)) {
            return "-";
        }
        AiModel model = modelMap.get(modelId);
        return model == null ? modelId : model.getName();
    }

    private static class TrendAggregation {
        private final LocalDateTime bucketTime;
        private final String modelId;
        private long requestCount;
        private long totalLatency;
        private BigDecimal totalCost = BigDecimal.ZERO;

        private TrendAggregation(LocalDateTime bucketTime, String modelId) {
            this.bucketTime = bucketTime;
            this.modelId = modelId;
        }

        private void add(Long latencyMs, BigDecimal cost) {
            this.requestCount++;
            this.totalLatency += latencyMs == null ? 0L : latencyMs;
            this.totalCost = this.totalCost.add(cost == null ? BigDecimal.ZERO : cost);
        }
    }
}
