package com.schemaplexai.service.cost.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.BudgetMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.Budget;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.model.vo.cost.CostByDimensionVO;
import com.schemaplexai.model.vo.cost.CostOverviewVO;
import com.schemaplexai.model.vo.cost.CostTrendVO;
import com.schemaplexai.service.cost.CostAnalysisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成本分析服务实现
 * 基于现有 Agent 执行记录与模型单价聚合成本数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostAnalysisServiceImpl implements CostAnalysisService {

    private final AgentExecutionMapper agentExecutionMapper;
    private final AiModelMapper aiModelMapper;
    private final SpecMapper specMapper;
    private final WorkspaceMapper workspaceMapper;
    private final AgentMapper agentMapper;
    private final BudgetMapper budgetMapper;

    @Override
    public CostOverviewVO overview(String timeRange) {
        log.info("查询成本概览: timeRange={}", timeRange);

        TimeWindow window = resolveWindow(timeRange, null, null, "day");
        List<ExecutionCostRow> rows = loadExecutionCosts(window.start(), window.end());
        BigDecimal totalCost = rows.stream()
                .map(ExecutionCostRow::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalTokens = rows.stream().mapToLong(ExecutionCostRow::tokens).sum();

        LocalDate today = LocalDate.now();
        BigDecimal todayCost = rows.stream()
                .filter(row -> row.createdDate().equals(today))
                .map(ExecutionCostRow::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long todayTokens = rows.stream()
                .filter(row -> row.createdDate().equals(today))
                .mapToLong(ExecutionCostRow::tokens)
                .sum();

        BigDecimal previousCost = loadExecutionCosts(window.previousStart(), window.start())
                .stream()
                .map(ExecutionCostRow::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        CostOverviewVO vo = new CostOverviewVO();
        vo.setTotalCost(scaleCost(totalCost));
        vo.setTotalTokens(totalTokens);
        vo.setTodayCost(scaleCost(todayCost));
        vo.setTodayTokens(todayTokens);
        vo.setCostChangeRate(calculateChangeRate(totalCost, previousCost));
        vo.setBudgetUsageRate(calculateBudgetUsageRate(totalCost));
        vo.setCostByModel(byModel(timeRange, null, null));
        return vo;
    }

    @Override
    public CostTrendVO trend(String timeRange, String startTime, String endTime, String groupBy) {
        log.info("查询成本趋势: timeRange={}, startTime={}, endTime={}, groupBy={}",
                timeRange, startTime, endTime, groupBy);

        TimeWindow window = resolveWindow(timeRange, startTime, endTime, groupBy);
        List<ExecutionCostRow> rows = loadExecutionCosts(window.start(), window.end());
        LinkedHashMap<String, AggregationBucket> grouped = groupRows(rows, ExecutionCostRow::createdAt, window);

        CostTrendVO vo = new CostTrendVO();
        vo.setCostTrend(grouped.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "label", entry.getKey(),
                        "date", entry.getKey(),
                        "value", scaleCost(entry.getValue().cost()),
                        "cost", scaleCost(entry.getValue().cost())
                ))
                .toList());
        vo.setTokenTrend(grouped.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "label", entry.getKey(),
                        "date", entry.getKey(),
                        "value", entry.getValue().tokens(),
                        "tokens", entry.getValue().tokens()
                ))
                .toList());
        return vo;
    }

    @Override
    public List<CostByDimensionVO> byProject(String timeRange, String startTime, String endTime) {
        log.info("查询按项目维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);
        TimeWindow window = resolveWindow(timeRange, startTime, endTime, "day");
        List<ExecutionCostRow> rows = loadExecutionCosts(window.start(), window.end());
        Map<String, Spec> specMap = loadSpecMap(rows.stream().map(ExecutionCostRow::specId).filter(StringUtils::hasText).toList());
        Map<String, Workspace> workspaceMap = loadWorkspaceMap(specMap.values().stream()
                .map(Spec::getProjectId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return aggregateDimension(rows, row -> {
            Spec spec = specMap.get(row.specId());
            if (spec == null || !StringUtils.hasText(spec.getProjectId())) {
                return new DimensionRef("unassigned", "未绑定工作空间");
            }
            Workspace workspace = workspaceMap.get(spec.getProjectId());
            return new DimensionRef(spec.getProjectId(), workspace == null ? spec.getProjectId() : workspace.getName());
        });
    }

    @Override
    public List<CostByDimensionVO> byModel(String timeRange, String startTime, String endTime) {
        log.info("查询按模型维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);
        TimeWindow window = resolveWindow(timeRange, startTime, endTime, "day");
        List<ExecutionCostRow> rows = loadExecutionCosts(window.start(), window.end());
        Map<String, AiModel> modelMap = loadModelMap(rows.stream().map(ExecutionCostRow::modelId).filter(StringUtils::hasText).toList());
        return aggregateDimension(rows, row -> {
            AiModel model = modelMap.get(row.modelId());
            String name = model != null && StringUtils.hasText(model.getName()) ? model.getName() : defaultText(row.modelId(), "未识别模型");
            return new DimensionRef(defaultText(row.modelId(), "unknown-model"), name);
        });
    }

    @Override
    public List<CostByDimensionVO> byAgent(String timeRange, String startTime, String endTime) {
        log.info("查询按Agent维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);
        TimeWindow window = resolveWindow(timeRange, startTime, endTime, "day");
        List<ExecutionCostRow> rows = loadExecutionCosts(window.start(), window.end());
        Map<String, Agent> agentMap = loadAgentMap(rows.stream().map(ExecutionCostRow::agentId).filter(StringUtils::hasText).toList());
        return aggregateDimension(rows, row -> {
            Agent agent = agentMap.get(row.agentId());
            String name = agent != null && StringUtils.hasText(agent.getName()) ? agent.getName() : defaultText(row.agentId(), "未绑定Agent");
            return new DimensionRef(defaultText(row.agentId(), "unknown-agent"), name);
        });
    }

    @Override
    public List<CostByDimensionVO> byUser(String timeRange, String startTime, String endTime) {
        log.info("查询按用户维度成本: timeRange={}, startTime={}, endTime={}", timeRange, startTime, endTime);
        TimeWindow window = resolveWindow(timeRange, startTime, endTime, "day");
        List<ExecutionCostRow> rows = loadExecutionCosts(window.start(), window.end());
        return aggregateDimension(rows, row -> new DimensionRef(
                defaultText(row.createdBy(), "unknown-user"),
                defaultText(row.createdBy(), "未知用户")
        ));
    }

    private List<ExecutionCostRow> loadExecutionCosts(LocalDateTime start, LocalDateTime end) {
        List<AgentExecution> executions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .ge(start != null, AgentExecution::getCreatedAt, start)
                .lt(end != null, AgentExecution::getCreatedAt, end)
                .orderByDesc(AgentExecution::getCreatedAt));
        Map<String, AiModel> modelMap = loadModelMap(executions.stream()
                .map(AgentExecution::getAiModel)
                .filter(StringUtils::hasText)
                .toList());
        return executions.stream()
                .map(execution -> toExecutionCostRow(
                        execution,
                        StringUtils.hasText(execution.getAiModel()) ? modelMap.get(execution.getAiModel()) : null
                ))
                .toList();
    }

    private ExecutionCostRow toExecutionCostRow(AgentExecution execution, AiModel model) {
        long tokenInput = execution.getTokenInput() == null ? 0L : execution.getTokenInput();
        long tokenOutput = execution.getTokenOutput() == null ? 0L : execution.getTokenOutput();
        BigDecimal inputPrice = model == null || model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice();
        BigDecimal outputPrice = model == null || model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice();
        BigDecimal cost = inputPrice.multiply(BigDecimal.valueOf(tokenInput))
                .add(outputPrice.multiply(BigDecimal.valueOf(tokenOutput)))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
        LocalDateTime createdAt = execution.getCreatedAt() == null ? LocalDateTime.now() : execution.getCreatedAt();
        return new ExecutionCostRow(
                execution.getId(),
                execution.getAgentId(),
                execution.getSpecId(),
                execution.getAiModel(),
                execution.getCreatedBy(),
                createdAt,
                createdAt.toLocalDate(),
                tokenInput + tokenOutput,
                cost
        );
    }

    private List<CostByDimensionVO> aggregateDimension(List<ExecutionCostRow> rows, Function<ExecutionCostRow, DimensionRef> classifier) {
        BigDecimal totalCost = rows.stream().map(ExecutionCostRow::cost).reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalTokens = rows.stream().mapToLong(ExecutionCostRow::tokens).sum();
        Map<DimensionRef, AggregationBucket> grouped = new LinkedHashMap<>();
        for (ExecutionCostRow row : rows) {
            DimensionRef ref = classifier.apply(row);
            grouped.computeIfAbsent(ref, key -> new AggregationBucket())
                    .add(row.cost(), row.tokens());
        }
        return grouped.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<DimensionRef, AggregationBucket> entry) -> entry.getValue().cost()).reversed())
                .map(entry -> {
                    CostByDimensionVO vo = new CostByDimensionVO();
                    vo.setDimensionId(entry.getKey().id());
                    vo.setDimensionName(entry.getKey().name());
                    vo.setTotalCost(scaleCost(entry.getValue().cost()));
                    vo.setTotalTokens(entry.getValue().tokens());
                    vo.setPercentage(calculatePercentage(entry.getValue().cost(), totalCost, entry.getValue().tokens(), totalTokens));
                    return vo;
                })
                .toList();
    }

    private LinkedHashMap<String, AggregationBucket> groupRows(List<ExecutionCostRow> rows,
                                                               Function<ExecutionCostRow, LocalDateTime> timeExtractor,
                                                               TimeWindow window) {
        LinkedHashMap<String, AggregationBucket> grouped = new LinkedHashMap<>();
        for (String key : buildTimeBucketKeys(window)) {
            grouped.put(key, new AggregationBucket());
        }
        for (ExecutionCostRow row : rows) {
            String key = formatGroupKey(timeExtractor.apply(row), window.groupBy());
            grouped.computeIfAbsent(key, ignored -> new AggregationBucket())
                    .add(row.cost(), row.tokens());
        }
        return grouped;
    }

    private List<String> buildTimeBucketKeys(TimeWindow window) {
        LocalDateTime bucketEnd = alignBucketStart(resolveBucketEnd(window), window.groupBy());
        Integer fixedBucketCount = resolveFixedBucketCount(window.timeRange(), window.groupBy());
        LocalDateTime bucketStart = fixedBucketCount == null
                ? alignBucketStart(window.start(), window.groupBy())
                : shiftBucket(bucketEnd, window.groupBy(), -(fixedBucketCount - 1));

        List<String> keys = new ArrayList<>();
        LocalDateTime cursor = bucketStart;
        while (!cursor.isAfter(bucketEnd)) {
            keys.add(formatGroupKey(cursor, window.groupBy()));
            cursor = shiftBucket(cursor, window.groupBy(), 1);
        }
        return keys;
    }

    private Integer resolveFixedBucketCount(String timeRange, String groupBy) {
        if (!StringUtils.hasText(timeRange) || !StringUtils.hasText(groupBy)) {
            return null;
        }
        if ("hour".equalsIgnoreCase(groupBy)
                && ("24h".equalsIgnoreCase(timeRange) || "last_24h".equalsIgnoreCase(timeRange))) {
            return 24;
        }
        if ("day".equalsIgnoreCase(groupBy)) {
            if ("7d".equalsIgnoreCase(timeRange) || "last_7d".equalsIgnoreCase(timeRange)) {
                return 7;
            }
            if ("30d".equalsIgnoreCase(timeRange) || "last_30d".equalsIgnoreCase(timeRange)) {
                return 30;
            }
        }
        return null;
    }

    private LocalDateTime resolveBucketEnd(TimeWindow window) {
        if (window.end() == null) {
            return LocalDateTime.now();
        }
        if (window.start() != null && window.end().isAfter(window.start())) {
            return window.end().minusNanos(1);
        }
        return window.end();
    }

    private LocalDateTime alignBucketStart(LocalDateTime time, String groupBy) {
        if (time == null) {
            return LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        }
        if ("month".equalsIgnoreCase(groupBy)) {
            return YearMonth.from(time).atDay(1).atStartOfDay();
        }
        if ("week".equalsIgnoreCase(groupBy)) {
            return time.toLocalDate().with(java.time.DayOfWeek.MONDAY).atStartOfDay();
        }
        if ("hour".equalsIgnoreCase(groupBy)) {
            return time.truncatedTo(ChronoUnit.HOURS);
        }
        return time.toLocalDate().atStartOfDay();
    }

    private LocalDateTime shiftBucket(LocalDateTime time, String groupBy, int step) {
        if ("month".equalsIgnoreCase(groupBy)) {
            return time.plusMonths(step);
        }
        if ("week".equalsIgnoreCase(groupBy)) {
            return time.plusWeeks(step);
        }
        if ("hour".equalsIgnoreCase(groupBy)) {
            return time.plusHours(step);
        }
        return time.plusDays(step);
    }

    private String formatGroupKey(LocalDateTime time, String groupBy) {
        if (time == null) {
            return "unknown";
        }
        if ("month".equalsIgnoreCase(groupBy)) {
            return YearMonth.from(time).toString();
        }
        if ("week".equalsIgnoreCase(groupBy)) {
            LocalDate monday = time.toLocalDate().with(java.time.DayOfWeek.MONDAY);
            return monday.toString();
        }
        if ("hour".equalsIgnoreCase(groupBy)) {
            return time.withMinute(0).withSecond(0).withNano(0).toString();
        }
        return time.toLocalDate().toString();
    }

    private Double calculateBudgetUsageRate(BigDecimal totalCost) {
        Budget budget = budgetMapper.selectOne(new LambdaQueryWrapper<Budget>()
                .eq(Budget::getStatus, "active")
                .orderByDesc(Budget::getCreatedAt)
                .last("LIMIT 1"));
        if (budget == null || budget.getBudgetAmount() == null || BigDecimal.ZERO.compareTo(budget.getBudgetAmount()) == 0) {
            return 0D;
        }
        return roundTwoDecimal(totalCost.multiply(BigDecimal.valueOf(100))
                .divide(budget.getBudgetAmount(), 4, RoundingMode.HALF_UP)
                .doubleValue());
    }

    private Double calculateChangeRate(BigDecimal current, BigDecimal previous) {
        if (previous == null || BigDecimal.ZERO.compareTo(previous) == 0) {
            return BigDecimal.ZERO.compareTo(current) == 0 ? 0D : 100D;
        }
        return roundTwoDecimal(current.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 4, RoundingMode.HALF_UP)
                .doubleValue());
    }

    private Double calculatePercentage(BigDecimal cost, BigDecimal totalCost, long tokens, long totalTokens) {
        if (totalCost != null && BigDecimal.ZERO.compareTo(totalCost) != 0) {
            return roundTwoDecimal(cost.multiply(BigDecimal.valueOf(100))
                    .divide(totalCost, 4, RoundingMode.HALF_UP)
                    .doubleValue());
        }
        if (totalTokens == 0) {
            return 0D;
        }
        return roundTwoDecimal(tokens * 100D / totalTokens);
    }

    private Map<String, AiModel> loadModelMap(List<String> modelIds) {
        if (modelIds == null || modelIds.isEmpty()) {
            return Map.of();
        }
        List<String> keys = modelIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        List<String> idKeys = keys.stream()
                .filter(this::looksLikeUuid)
                .toList();
        List<String> nameKeys = keys.stream()
                .filter(key -> !looksLikeUuid(key))
                .toList();
        List<AiModel> models = new ArrayList<>();
        if (!idKeys.isEmpty()) {
            models.addAll(aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                    .in(AiModel::getId, idKeys)));
        }
        if (!nameKeys.isEmpty()) {
            models.addAll(aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                    .in(AiModel::getName, nameKeys)));
        }
        Map<String, AiModel> result = new LinkedHashMap<>();
        for (AiModel model : models) {
            if (model == null) {
                continue;
            }
            if (StringUtils.hasText(model.getId())) {
                result.putIfAbsent(model.getId(), model);
            }
            if (StringUtils.hasText(model.getName())) {
                result.putIfAbsent(model.getName(), model);
            }
        }
        return result;
    }

    private boolean looksLikeUuid(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    }

    private Map<String, Spec> loadSpecMap(List<String> specIds) {
        if (specIds == null || specIds.isEmpty()) {
            return Map.of();
        }
        return specMapper.selectBatchIds(new ArrayList<>(specIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Spec::getId, spec -> spec, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, Workspace> loadWorkspaceMap(Collection<String> workspaceIds) {
        if (workspaceIds == null || workspaceIds.isEmpty()) {
            return Map.of();
        }
        return workspaceMapper.selectBatchIds(new ArrayList<>(workspaceIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Workspace::getId, workspace -> workspace, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, Agent> loadAgentMap(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        return agentMapper.selectBatchIds(new ArrayList<>(agentIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Agent::getId, agent -> agent, (left, right) -> left, LinkedHashMap::new));
    }

    private TimeWindow resolveWindow(String timeRange, String startTime, String endTime, String groupBy) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start;
        LocalDateTime end;
        if ("custom".equalsIgnoreCase(timeRange) && StringUtils.hasText(startTime) && StringUtils.hasText(endTime)) {
            start = LocalDateTime.parse(startTime);
            end = LocalDateTime.parse(endTime);
        } else if ("24h".equalsIgnoreCase(timeRange) || "last_24h".equalsIgnoreCase(timeRange)) {
            start = now.minusHours(24);
            end = now;
        } else if ("30d".equalsIgnoreCase(timeRange) || "last_30d".equalsIgnoreCase(timeRange)) {
            start = now.minusDays(30);
            end = now;
        } else {
            start = now.minusDays(7);
            end = now;
        }
        LocalDateTime previousStart = start.minusSeconds(java.time.Duration.between(start, end).getSeconds());
        return new TimeWindow(start, end, previousStart, normalizeGroupBy(groupBy),
                StringUtils.hasText(timeRange) ? timeRange : "7d");
    }

    private String normalizeGroupBy(String groupBy) {
        if (!StringUtils.hasText(groupBy)) {
            return "day";
        }
        return groupBy;
    }

    private BigDecimal scaleCost(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private Double roundTwoDecimal(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private record TimeWindow(LocalDateTime start, LocalDateTime end, LocalDateTime previousStart, String groupBy, String timeRange) {
    }

    private record ExecutionCostRow(String executionId,
                                    String agentId,
                                    String specId,
                                    String modelId,
                                    String createdBy,
                                    LocalDateTime createdAt,
                                    LocalDate createdDate,
                                    long tokens,
                                    BigDecimal cost) {
    }

    private record DimensionRef(String id, String name) {
    }

    private static final class AggregationBucket {
        private BigDecimal cost = BigDecimal.ZERO;
        private long tokens = 0L;

        private void add(BigDecimal itemCost, long itemTokens) {
            cost = cost.add(itemCost == null ? BigDecimal.ZERO : itemCost);
            tokens += itemTokens;
        }

        private BigDecimal cost() {
            return cost;
        }

        private long tokens() {
            return tokens;
        }
    }
}
