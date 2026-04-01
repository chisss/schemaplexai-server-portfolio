package com.schemaplexai.service.monitor.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.CrossReviewMapper;
import com.schemaplexai.dao.mapper.IntentDefectMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.ReportTemplateMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.converter.ReportTemplateConverter;
import com.schemaplexai.model.dto.monitor.ReportQueryRequest;
import com.schemaplexai.model.dto.monitor.ReportTemplateCreateRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.IntentDefect;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.ReportTemplate;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.model.vo.monitor.ReportDataVO;
import com.schemaplexai.model.vo.monitor.ReportTemplateVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.monitor.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 报表服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    private final ReportTemplateMapper reportTemplateMapper;
    private final ReportTemplateConverter reportTemplateConverter;
    private final EntityValidator entityValidator;
    private final AgentExecutionMapper agentExecutionMapper;
    private final QualityDeviationMapper qualityDeviationMapper;
    private final IntentDefectMapper intentDefectMapper;
    private final CrossReviewMapper crossReviewMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final SpecMapper specMapper;
    private final WorkspaceMapper workspaceMapper;
    private final AgentMapper agentMapper;

    @Override
    public ReportDataVO getEfficiencyReport(ReportQueryRequest request) {
        log.info("查询效率报表, request={}", request);

        TimeWindow window = resolveWindow(request);
        List<AgentExecution> executions = loadExecutions(window);
        long totalTasks = executions.size();
        long completedTasks = executions.stream()
                .filter(execution -> AgentExecutionStatusEnum.COMPLETED.getCode().equals(execution.getStatus()))
                .count();
        long failedTasks = executions.stream()
                .filter(execution -> AgentExecutionStatusEnum.FAILED.getCode().equals(execution.getStatus()))
                .count();
        long avgDurationMs = calculateAverageDuration(executions);
        LinkedHashMap<String, AggregationBucket> trendBuckets = groupByTime(executions, AgentExecution::getCreatedAt, window);

        ReportDataVO report = new ReportDataVO();
        report.setColumns(List.of("时间", "任务数", "完成数", "失败数", "平均耗时(ms)", "成功率(%)"));
        report.setRows(trendBuckets.entrySet().stream()
                .map(entry -> row(
                        entry.getKey(),
                        entry.getValue().count(),
                        entry.getValue().successCount(),
                        entry.getValue().failureCount(),
                        entry.getValue().avgDurationMs(),
                        roundTwoDecimal(entry.getValue().successRate())
                ))
                .toList());
        report.setSummary(Map.of(
                "totalTasks", totalTasks,
                "completedTasks", completedTasks,
                "failedTasks", failedTasks,
                "completionRate", totalTasks == 0 ? 0D : roundTwoDecimal(completedTasks * 100D / totalTasks),
                "avgDurationMs", avgDurationMs,
                "activeWorkflowCount", workflowInstanceMapper.selectCount(new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getStatus, "running"))
        ));
        report.setTrend(trendBuckets.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "label", entry.getKey(),
                        "taskCount", entry.getValue().count(),
                        "successRate", roundTwoDecimal(entry.getValue().successRate()),
                        "avgDurationMs", entry.getValue().avgDurationMs()
                ))
                .toList());
        return report;
    }

    @Override
    public ReportDataVO getQualityReport(ReportQueryRequest request) {
        log.info("查询质量报表, request={}", request);

        TimeWindow window = resolveWindow(request);
        List<QualityDeviation> deviations = qualityDeviationMapper.selectList(new LambdaQueryWrapper<QualityDeviation>()
                .ge(QualityDeviation::getCreatedAt, window.start())
                .lt(QualityDeviation::getCreatedAt, window.end())
                .orderByDesc(QualityDeviation::getCreatedAt));
        List<IntentDefect> defects = intentDefectMapper.selectList(new LambdaQueryWrapper<IntentDefect>()
                .ge(IntentDefect::getCreatedAt, window.start())
                .lt(IntentDefect::getCreatedAt, window.end())
                .orderByDesc(IntentDefect::getCreatedAt));
        List<CrossReview> reviews = crossReviewMapper.selectList(new LambdaQueryWrapper<CrossReview>()
                .ge(CrossReview::getCreatedAt, window.start())
                .lt(CrossReview::getCreatedAt, window.end())
                .orderByDesc(CrossReview::getCreatedAt));
        Map<String, Spec> specMap = loadSpecMap(deviations.stream().map(QualityDeviation::getSpecId).filter(StringUtils::hasText).toList());
        Map<String, Long> specDeviationCount = deviations.stream()
                .collect(Collectors.groupingBy(QualityDeviation::getSpecId, LinkedHashMap::new, Collectors.counting()));
        LinkedHashMap<String, QualityAggregationBucket> trendBuckets = groupQualityByTime(deviations, defects, window);

        ReportDataVO report = new ReportDataVO();
        report.setColumns(List.of("Spec", "偏离数", "严重偏离", "未闭环数"));
        report.setRows(specDeviationCount.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(entry -> row(
                        resolveSpecName(specMap.get(entry.getKey()), entry.getKey()),
                        entry.getValue(),
                        deviations.stream().filter(item -> Objects.equals(item.getSpecId(), entry.getKey()) && "critical".equalsIgnoreCase(item.getSeverity())).count(),
                        deviations.stream().filter(item -> Objects.equals(item.getSpecId(), entry.getKey()) && !"resolved".equalsIgnoreCase(item.getStatus())).count()
                ))
                .toList());
        long totalIssues = deviations.size() + defects.size();
        long unresolvedCount = deviations.stream().filter(item -> !"resolved".equalsIgnoreCase(item.getStatus())).count();
        report.setSummary(Map.of(
                "totalDeviations", deviations.size(),
                "totalIntentDefects", defects.size(),
                "crossReviewCount", reviews.size(),
                "criticalDeviations", deviations.stream().filter(item -> "critical".equalsIgnoreCase(item.getSeverity())).count(),
                "closureRate", deviations.isEmpty() ? 100D : roundTwoDecimal((deviations.size() - unresolvedCount) * 100D / deviations.size()),
                "issueDensity", totalIssues
        ));
        report.setTrend(trendBuckets.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "label", entry.getKey(),
                        "deviationCount", entry.getValue().deviationCount(),
                        "intentDefectCount", entry.getValue().intentCount(),
                        "criticalCount", entry.getValue().criticalCount()
                ))
                .toList());
        return report;
    }

    @Override
    public ReportDataVO getAgentPerformanceReport(ReportQueryRequest request, String agentIds) {
        log.info("查询Agent性能报表, request={}, agentIds={}", request, agentIds);

        TimeWindow window = resolveWindow(request);
        Set<String> filterIds = parseFilterIds(agentIds);
        List<AgentExecution> executions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .ge(AgentExecution::getCreatedAt, window.start())
                .lt(AgentExecution::getCreatedAt, window.end())
                .in(!filterIds.isEmpty(), AgentExecution::getAgentId, filterIds)
                .orderByDesc(AgentExecution::getCreatedAt));
        Map<String, Agent> agentMap = loadAgentMap(executions.stream().map(AgentExecution::getAgentId).filter(StringUtils::hasText).toList());
        Map<String, AggregationBucket> grouped = new LinkedHashMap<>();
        for (AgentExecution execution : executions) {
            String agentId = defaultText(execution.getAgentId(), "unknown-agent");
            grouped.computeIfAbsent(agentId, ignored -> new AggregationBucket())
                    .addExecution(execution);
        }

        ReportDataVO report = new ReportDataVO();
        report.setColumns(List.of("Agent", "执行数", "成功率(%)", "平均耗时(ms)", "输入Token", "输出Token"));
        report.setRows(grouped.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().count(), left.getValue().count()))
                .map(entry -> row(
                        resolveAgentName(agentMap.get(entry.getKey()), entry.getKey()),
                        entry.getValue().count(),
                        roundTwoDecimal(entry.getValue().successRate()),
                        entry.getValue().avgDurationMs(),
                        entry.getValue().tokenInput(),
                        entry.getValue().tokenOutput()
                ))
                .toList());
        report.setSummary(Map.of(
                "agentCount", grouped.size(),
                "executionCount", executions.size(),
                "avgSuccessRate", grouped.isEmpty() ? 0D : roundTwoDecimal(grouped.values().stream().mapToDouble(AggregationBucket::successRate).average().orElse(0D)),
                "avgDurationMs", grouped.isEmpty() ? 0L : Math.round(grouped.values().stream().mapToLong(AggregationBucket::avgDurationMs).average().orElse(0D))
        ));
        report.setTrend(groupByTime(executions, AgentExecution::getCreatedAt, window).entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "label", entry.getKey(),
                        "executionCount", entry.getValue().count(),
                        "successRate", roundTwoDecimal(entry.getValue().successRate())
                ))
                .toList());
        return report;
    }

    @Override
    public ReportDataVO getProjectProgressReport(ReportQueryRequest request) {
        log.info("查询项目进度报表, request={}", request);

        List<Spec> specs = specMapper.selectList(new LambdaQueryWrapper<Spec>().orderByDesc(Spec::getCreatedAt));
        Map<String, Workspace> workspaceMap = loadWorkspaceMap(specs.stream()
                .map(Spec::getProjectId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new)));
        Map<String, List<Spec>> grouped = specs.stream()
                .collect(Collectors.groupingBy(spec -> defaultText(spec.getProjectId(), "unassigned"), LinkedHashMap::new, Collectors.toList()));

        ReportDataVO report = new ReportDataVO();
        report.setColumns(List.of("工作空间", "Spec总数", "已归档", "进行中", "完成率(%)"));
        report.setRows(grouped.entrySet().stream()
                .map(entry -> {
                    List<Spec> projectSpecs = entry.getValue();
                    long archived = projectSpecs.stream().filter(spec -> "archived".equalsIgnoreCase(spec.getStatus())).count();
                    long inProgress = projectSpecs.stream().filter(spec -> "in_progress".equalsIgnoreCase(spec.getStatus())).count();
                    return row(
                            resolveWorkspaceName(workspaceMap.get(entry.getKey()), entry.getKey()),
                            projectSpecs.size(),
                            archived,
                            inProgress,
                            projectSpecs.isEmpty() ? 0D : roundTwoDecimal(archived * 100D / projectSpecs.size())
                    );
                })
                .toList());
        report.setSummary(Map.of(
                "workspaceCount", grouped.size(),
                "specCount", specs.size(),
                "archivedCount", specs.stream().filter(spec -> "archived".equalsIgnoreCase(spec.getStatus())).count(),
                "workflowRunningCount", workflowInstanceMapper.selectCount(new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getStatus, "running"))
        ));
        report.setTrend(grouped.entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "label", resolveWorkspaceName(workspaceMap.get(entry.getKey()), entry.getKey()),
                        "specCount", entry.getValue().size(),
                        "archivedCount", entry.getValue().stream().filter(spec -> "archived".equalsIgnoreCase(spec.getStatus())).count()
                ))
                .toList());
        return report;
    }

    @Override
    public ReportDataVO getTeamCollaborationReport(ReportQueryRequest request) {
        log.info("查询团队协作报表, request={}", request);

        TimeWindow window = resolveWindow(request);
        List<Spec> specs = specMapper.selectList(new LambdaQueryWrapper<Spec>()
                .ge(Spec::getCreatedAt, window.start())
                .lt(Spec::getCreatedAt, window.end())
                .orderByDesc(Spec::getCreatedAt));
        List<WorkflowNodeExecution> pendingReviews = workflowNodeExecutionMapper.selectList(new LambdaQueryWrapper<WorkflowNodeExecution>()
                .eq(WorkflowNodeExecution::getStatus, "pending")
                .orderByDesc(WorkflowNodeExecution::getCreatedAt));
        Map<String, WorkflowInstance> workflowInstanceMap = workflowInstanceMapper.selectBatchIds(pendingReviews.stream()
                        .map(WorkflowNodeExecution::getInstanceId)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.toCollection(LinkedHashSet::new)))
                .stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(WorkflowInstance::getId, instance -> instance, (left, right) -> left, LinkedHashMap::new));
        Map<String, Long> ownerPendingCount = new LinkedHashMap<>();
        for (WorkflowNodeExecution node : pendingReviews) {
            WorkflowInstance instance = workflowInstanceMap.get(node.getInstanceId());
            String owner = instance == null ? "unknown-user" : defaultText(instance.getCreatedBy(), "unknown-user");
            ownerPendingCount.merge(owner, 1L, Long::sum);
        }

        Map<String, Long> ownerCount = specs.stream()
                .collect(Collectors.groupingBy(spec -> defaultText(spec.getOwner(), "unknown-user"), LinkedHashMap::new, Collectors.counting()));
        ReportDataVO report = new ReportDataVO();
        report.setColumns(List.of("协作者", "参与Spec数", "待处理审批"));
        report.setRows(ownerCount.entrySet().stream()
                .map(entry -> row(
                        entry.getKey(),
                        entry.getValue(),
                        ownerPendingCount.getOrDefault(entry.getKey(), 0L)
                ))
                .toList());
        report.setSummary(Map.of(
                "activeCollaboratorCount", ownerCount.size(),
                "specCreatedCount", specs.size(),
                "pendingApprovalCount", pendingReviews.size(),
                "crossReviewCount", crossReviewMapper.selectCount(new LambdaQueryWrapper<CrossReview>()
                        .ge(CrossReview::getCreatedAt, window.start())
                        .lt(CrossReview::getCreatedAt, window.end()))
        ));
        report.setTrend(groupByTime(specs, Spec::getCreatedAt, window).entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "label", entry.getKey(),
                        "specCount", entry.getValue().count()
                ))
                .toList());
        return report;
    }

    @Override
    public ReportDataVO customQuery(Map<String, Object> queryConfig) {
        log.info("执行自定义报表查询, queryConfig={}", queryConfig);

        String reportType = queryConfig == null ? null : String.valueOf(queryConfig.getOrDefault("reportType", ""));
        ReportQueryRequest request = new ReportQueryRequest();
        if (queryConfig != null) {
            request.setTimeRange(asText(queryConfig.get("timeRange")));
            request.setStartTime(asDateTime(queryConfig.get("startTime")));
            request.setEndTime(asDateTime(queryConfig.get("endTime")));
            request.setGroupBy(asText(queryConfig.get("groupBy")));
        }
        if ("quality".equalsIgnoreCase(reportType)) {
            return getQualityReport(request);
        }
        if ("agent_performance".equalsIgnoreCase(reportType)) {
            return getAgentPerformanceReport(request, asText(queryConfig == null ? null : queryConfig.get("agentIds")));
        }
        if ("project_progress".equalsIgnoreCase(reportType)) {
            return getProjectProgressReport(request);
        }
        if ("team_collaboration".equalsIgnoreCase(reportType)) {
            return getTeamCollaborationReport(request);
        }
        return getEfficiencyReport(request);
    }

    @Override
    public PageResult<ReportTemplateVO> pageTemplates(String reportType, Integer page, Integer size) {
        log.info("分页查询报表模板, reportType={}, page={}, size={}", reportType, page, size);

        Page<ReportTemplate> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<ReportTemplate> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(reportType)) {
            wrapper.eq(ReportTemplate::getReportType, reportType);
        }
        wrapper.orderByDesc(ReportTemplate::getCreatedAt);

        var result = reportTemplateMapper.selectPage(pageParam, wrapper);
        var voList = reportTemplateConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportTemplateVO createTemplate(ReportTemplateCreateRequest request) {
        log.info("创建报表模板, name={}, reportType={}", request.getName(), request.getReportType());

        ReportTemplate template = reportTemplateConverter.fromCreateRequest(request);
        template.setTenantId(SecurityUtil.getCurrentTenantId());
        template.setCreatedBy(SecurityUtil.getCurrentUserId());
        reportTemplateMapper.insert(template);
        return reportTemplateConverter.toVO(template);
    }

    private List<AgentExecution> loadExecutions(TimeWindow window) {
        return agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .ge(AgentExecution::getCreatedAt, window.start())
                .lt(AgentExecution::getCreatedAt, window.end())
                .orderByDesc(AgentExecution::getCreatedAt));
    }

    private <T> LinkedHashMap<String, AggregationBucket> groupByTime(List<T> items,
                                                                     Function<T, LocalDateTime> timeExtractor,
                                                                     TimeWindow window) {
        LinkedHashMap<String, AggregationBucket> grouped = initializeBuckets(buildTimeBucketKeys(window), AggregationBucket::new);
        for (T item : items) {
            String key = formatGroupKey(timeExtractor.apply(item), window.groupBy());
            grouped.computeIfAbsent(key, ignored -> new AggregationBucket())
                    .addItem(item);
        }
        return grouped;
    }

    private LinkedHashMap<String, QualityAggregationBucket> groupQualityByTime(List<QualityDeviation> deviations,
                                                                               List<IntentDefect> defects,
                                                                               TimeWindow window) {
        LinkedHashMap<String, QualityAggregationBucket> grouped = initializeBuckets(buildTimeBucketKeys(window), QualityAggregationBucket::new);
        for (QualityDeviation deviation : deviations) {
            String key = formatGroupKey(deviation.getCreatedAt(), window.groupBy());
            grouped.computeIfAbsent(key, ignored -> new QualityAggregationBucket())
                    .addDeviation(deviation);
        }
        for (IntentDefect defect : defects) {
            String key = formatGroupKey(defect.getCreatedAt(), window.groupBy());
            grouped.computeIfAbsent(key, ignored -> new QualityAggregationBucket())
                    .addIntent(defect);
        }
        return grouped;
    }

    private <T> LinkedHashMap<String, T> initializeBuckets(List<String> keys, Supplier<T> supplier) {
        LinkedHashMap<String, T> buckets = new LinkedHashMap<>();
        for (String key : keys) {
            buckets.put(key, supplier.get());
        }
        return buckets;
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
            return time.toLocalDate().with(java.time.DayOfWeek.MONDAY).toString();
        }
        if ("hour".equalsIgnoreCase(groupBy)) {
            return time.withMinute(0).withSecond(0).withNano(0).toString();
        }
        return time.toLocalDate().toString();
    }

    private long calculateAverageDuration(List<AgentExecution> executions) {
        return Math.round(executions.stream()
                .mapToLong(execution -> {
                    if (execution.getStartedAt() == null || execution.getCompletedAt() == null) {
                        return 0L;
                    }
                    return java.time.Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toMillis();
                })
                .filter(duration -> duration > 0)
                .average()
                .orElse(0D));
    }

    private TimeWindow resolveWindow(ReportQueryRequest request) {
        String timeRange = request == null ? null : request.getTimeRange();
        LocalDateTime startTime = request == null ? null : request.getStartTime();
        LocalDateTime endTime = request == null ? null : request.getEndTime();
        String groupBy = request == null ? "day" : request.getGroupBy();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start;
        LocalDateTime end;
        if ("custom".equalsIgnoreCase(timeRange) && startTime != null && endTime != null) {
            start = startTime;
            end = endTime;
        } else if ("24h".equalsIgnoreCase(timeRange) || "last_24h".equalsIgnoreCase(timeRange)) {
            start = now.minusHours(24);
            end = now;
        } else if ("30d".equalsIgnoreCase(timeRange) || "last_30d".equalsIgnoreCase(timeRange)) {
            start = now.minusDays(30);
            end = now;
        } else if ("7d".equalsIgnoreCase(timeRange) || "last_7d".equalsIgnoreCase(timeRange)) {
            start = now.minusDays(7);
            end = now;
        } else {
            start = now.minusDays(7);
            end = now;
        }
        return new TimeWindow(start, end, StringUtils.hasText(groupBy) ? groupBy : "day",
                StringUtils.hasText(timeRange) ? timeRange : "last_7d");
    }

    private Set<String> parseFilterIds(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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

    private String resolveSpecName(Spec spec, String fallback) {
        return spec != null && StringUtils.hasText(spec.getName()) ? spec.getName() : defaultText(fallback, "未绑定Spec");
    }

    private String resolveWorkspaceName(Workspace workspace, String fallback) {
        return workspace != null && StringUtils.hasText(workspace.getName()) ? workspace.getName() : defaultText(fallback, "未绑定工作空间");
    }

    private String resolveAgentName(Agent agent, String fallback) {
        return agent != null && StringUtils.hasText(agent.getName()) ? agent.getName() : defaultText(fallback, "未绑定Agent");
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    private Double roundTwoDecimal(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime asDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return LocalDateTime.parse(String.valueOf(value));
    }

    private List<Object> row(Object... values) {
        return Arrays.asList(values);
    }

    private record TimeWindow(LocalDateTime start, LocalDateTime end, String groupBy, String timeRange) {
    }

    private static final class AggregationBucket {
        private long count;
        private long successCount;
        private long failureCount;
        private long totalDurationMs;
        private long durationCount;
        private long tokenInput;
        private long tokenOutput;

        private void addItem(Object item) {
            count++;
            if (item instanceof AgentExecution execution) {
                if (AgentExecutionStatusEnum.COMPLETED.getCode().equals(execution.getStatus())) {
                    successCount++;
                }
                if (AgentExecutionStatusEnum.FAILED.getCode().equals(execution.getStatus())) {
                    failureCount++;
                }
                if (execution.getStartedAt() != null && execution.getCompletedAt() != null) {
                    totalDurationMs += java.time.Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toMillis();
                    durationCount++;
                }
                tokenInput += execution.getTokenInput() == null ? 0L : execution.getTokenInput();
                tokenOutput += execution.getTokenOutput() == null ? 0L : execution.getTokenOutput();
            }
        }

        private long count() {
            return count;
        }

        private long successCount() {
            return successCount;
        }

        private long failureCount() {
            return failureCount;
        }

        private long avgDurationMs() {
            return durationCount == 0 ? 0L : Math.round(totalDurationMs * 1D / durationCount);
        }

        private double successRate() {
            return count == 0 ? 0D : successCount * 100D / count;
        }

        private long tokenInput() {
            return tokenInput;
        }

        private long tokenOutput() {
            return tokenOutput;
        }

        private void addExecution(AgentExecution execution) {
            addItem(execution);
        }
    }

    private static final class QualityAggregationBucket {
        private long deviationCount;
        private long intentCount;
        private long criticalCount;

        private void addDeviation(QualityDeviation deviation) {
            deviationCount++;
            if ("critical".equalsIgnoreCase(deviation.getSeverity())) {
                criticalCount++;
            }
        }

        private void addIntent(IntentDefect defect) {
            intentCount++;
            if ("critical".equalsIgnoreCase(defect.getSeverity())) {
                criticalCount++;
            }
        }

        private long deviationCount() {
            return deviationCount;
        }

        private long intentCount() {
            return intentCount;
        }

        private long criticalCount() {
            return criticalCount;
        }
    }
}
