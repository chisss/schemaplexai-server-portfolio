package com.schemaplexai.service.monitor.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.monitor.ActiveAgentVO;
import com.schemaplexai.model.vo.monitor.DashboardVO;
import com.schemaplexai.model.vo.monitor.SystemHealthVO;
import com.schemaplexai.model.vo.monitor.TaskQueueVO;
import com.schemaplexai.service.clickhouse.ClickHouseAnalyticsService;
import com.schemaplexai.service.monitor.DashboardService;
import com.sun.management.OperatingSystemMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 监控仪表盘服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    private static final Set<String> ACTIVE_STATUSES = Set.of(
            AgentExecutionStatusEnum.QUEUED.getCode(),
            AgentExecutionStatusEnum.RUNNING.getCode()
    );

    private final AgentExecutionMapper agentExecutionMapper;
    private final AgentMapper agentMapper;
    private final QualityDeviationMapper qualityDeviationMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectProvider<ClickHouseAnalyticsService> clickHouseAnalyticsServiceProvider;

    @Value("${schemaplexai.workspace.root-path:${user.home}/.schemaplexai/workspaces}")
    private String workspaceRootPath;

    @Override
    public DashboardVO getDashboard() {
        log.info("获取仪表盘概览数据");

        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime now = LocalDateTime.now();
        List<AgentExecution> executions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .ge(AgentExecution::getCreatedAt, todayStart.minusDays(29))
                .orderByDesc(AgentExecution::getCreatedAt));
        List<QualityDeviation> unresolvedDeviations = qualityDeviationMapper.selectList(new LambdaQueryWrapper<QualityDeviation>()
                .in(QualityDeviation::getStatus, List.of(
                        DeviationStatusEnum.OPEN.getCode(),
                        DeviationStatusEnum.ACKNOWLEDGED.getCode()
                )));
        long pendingApprovals = workflowNodeExecutionMapper.selectCount(new LambdaQueryWrapper<WorkflowNodeExecution>()
                .eq(WorkflowNodeExecution::getStatus, WorkflowInstanceStatusEnum.PENDING.getCode()));

        DashboardVO dashboard = new DashboardVO();
        dashboard.setActiveAgentCount((int) executions.stream()
                .filter(execution -> ACTIVE_STATUSES.contains(execution.getStatus()))
                .count());
        dashboard.setTaskQueueDepth((int) executions.stream()
                .filter(execution -> AgentExecutionStatusEnum.QUEUED.getCode().equals(execution.getStatus()))
                .count());
        dashboard.setTaskSuccessRate(calculateSuccessRate(executions, todayStart));
        dashboard.setAvgExecutionTimeMs(calculateAverageDuration(executions, todayStart));
        dashboard.setTodayTokenConsumption(executions.stream()
                .filter(execution -> execution.getCreatedAt() != null && !execution.getCreatedAt().isBefore(todayStart))
                .mapToLong(this::calculateTotalTokens)
                .sum());
        dashboard.setDeviationAlertCount((int) unresolvedDeviations.size());
        dashboard.setPendingApprovalCount((int) pendingApprovals);
        dashboard.setSystemHealth(getSystemHealth());
        return dashboard;
    }

    @Override
    public List<ActiveAgentVO> getActiveAgents() {
        log.info("获取当前活跃Agent列表");

        List<AgentExecution> activeExecutions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .in(AgentExecution::getStatus, ACTIVE_STATUSES)
                .orderByDesc(AgentExecution::getStartedAt)
                .orderByDesc(AgentExecution::getCreatedAt));
        Map<String, String> agentNameMap = loadAgentNameMap(activeExecutions.stream()
                .map(AgentExecution::getAgentId)
                .filter(StringUtils::hasText)
                .toList());
        return activeExecutions.stream()
                .limit(20)
                .map(execution -> {
                    ActiveAgentVO vo = new ActiveAgentVO();
                    vo.setAgentId(execution.getAgentId());
                    vo.setAgentName(agentNameMap.getOrDefault(execution.getAgentId(), execution.getAgentId()));
                    vo.setAgentType(resolveAgentType(execution.getAgentId()));
                    vo.setCurrentTaskId(execution.getTaskId());
                    vo.setProgress(resolveExecutionProgress(execution));
                    vo.setStartTime(execution.getStartedAt() != null ? execution.getStartedAt() : execution.getCreatedAt());
                    return vo;
                })
                .toList();
    }

    @Override
    public TaskQueueVO getTaskQueue() {
        log.info("获取任务队列状态");

        List<AgentExecution> queueExecutions = agentExecutionMapper.selectList(new LambdaQueryWrapper<AgentExecution>()
                .in(AgentExecution::getStatus, ACTIVE_STATUSES)
                .orderByAsc(AgentExecution::getCreatedAt));
        TaskQueueVO taskQueue = new TaskQueueVO();
        taskQueue.setTotalPending((int) queueExecutions.stream()
                .filter(execution -> AgentExecutionStatusEnum.QUEUED.getCode().equals(execution.getStatus()))
                .count());
        taskQueue.setTotalExecuting((int) queueExecutions.stream()
                .filter(execution -> AgentExecutionStatusEnum.RUNNING.getCode().equals(execution.getStatus()))
                .count());
        taskQueue.setQueueByPriority(Map.of(
                "high", taskQueue.getTotalExecuting(),
                "normal", taskQueue.getTotalPending(),
                "low", 0
        ));
        LocalDateTime oldestPending = queueExecutions.stream()
                .filter(execution -> AgentExecutionStatusEnum.QUEUED.getCode().equals(execution.getStatus()))
                .map(AgentExecution::getCreatedAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        taskQueue.setOldestTaskWaitMs(oldestPending == null
                ? 0L
                : java.time.Duration.between(oldestPending, LocalDateTime.now()).toMillis());
        return taskQueue;
    }

    @Override
    public SystemHealthVO getSystemHealth() {
        log.info("获取系统健康状态");

        SystemHealthVO health = new SystemHealthVO();
        health.setCpuUsage(resolveCpuUsage());
        health.setMemoryUsage(resolveMemoryUsage());
        health.setDiskUsage(resolveDiskUsage());

        Map<String, String> serviceStatus = new LinkedHashMap<>();
        serviceStatus.put("postgresql", checkPostgreSql());
        serviceStatus.put("redis", checkRedis());
        serviceStatus.put("rabbitmq", checkRabbitMq());
        serviceStatus.put("clickhouse", checkClickHouse());
        health.setServiceStatus(serviceStatus);
        health.setStatus(resolveOverallStatus(serviceStatus, health));
        return health;
    }

    private double calculateSuccessRate(List<AgentExecution> executions, LocalDateTime start) {
        List<AgentExecution> todayExecutions = executions.stream()
                .filter(execution -> execution.getCreatedAt() != null && !execution.getCreatedAt().isBefore(start))
                .filter(execution -> StringUtils.hasText(execution.getStatus()))
                .toList();
        long completedCount = todayExecutions.stream()
                .filter(execution -> AgentExecutionStatusEnum.COMPLETED.getCode().equals(execution.getStatus())
                        || AgentExecutionStatusEnum.STOPPED.getCode().equals(execution.getStatus()))
                .count();
        long failedCount = todayExecutions.stream()
                .filter(execution -> AgentExecutionStatusEnum.FAILED.getCode().equals(execution.getStatus()))
                .count();
        long total = completedCount + failedCount;
        if (total == 0) {
            return 0D;
        }
        return roundTwoDecimal(completedCount * 100D / total);
    }

    private long calculateAverageDuration(List<AgentExecution> executions, LocalDateTime start) {
        List<Long> durations = executions.stream()
                .filter(execution -> execution.getCreatedAt() != null && !execution.getCreatedAt().isBefore(start))
                .map(this::calculateDurationMs)
                .filter(duration -> duration > 0)
                .toList();
        if (durations.isEmpty()) {
            return 0L;
        }
        return Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(0D));
    }

    private long calculateDurationMs(AgentExecution execution) {
        if (execution.getStartedAt() == null || execution.getCompletedAt() == null) {
            return 0L;
        }
        return java.time.Duration.between(execution.getStartedAt(), execution.getCompletedAt()).toMillis();
    }

    private long calculateTotalTokens(AgentExecution execution) {
        return (execution.getTokenInput() == null ? 0L : execution.getTokenInput())
                + (execution.getTokenOutput() == null ? 0L : execution.getTokenOutput());
    }

    private Map<String, String> loadAgentNameMap(List<String> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) {
            return Map.of();
        }
        return agentMapper.selectBatchIds(new ArrayList<>(agentIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Agent::getId, Agent::getName, (left, right) -> left, LinkedHashMap::new));
    }

    private String resolveAgentType(String agentId) {
        if (!StringUtils.hasText(agentId)) {
            return "unknown";
        }
        Agent agent = agentMapper.selectById(agentId);
        return agent == null ? "unknown" : defaultText(agent.getAgentType(), "solo");
    }

    private Integer resolveExecutionProgress(AgentExecution execution) {
        if (execution == null || !StringUtils.hasText(execution.getStatus())) {
            return 0;
        }
        if (AgentExecutionStatusEnum.QUEUED.getCode().equals(execution.getStatus())) {
            return 15;
        }
        if (AgentExecutionStatusEnum.RUNNING.getCode().equals(execution.getStatus())) {
            if (execution.getStartedAt() == null) {
                return 50;
            }
            long runningMinutes = Math.max(1, java.time.Duration.between(execution.getStartedAt(), LocalDateTime.now()).toMinutes());
            return (int) Math.min(95, 35 + runningMinutes * 5);
        }
        return 100;
    }

    private Double resolveCpuUsage() {
        try {
            OperatingSystemMXBean osBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double usage = osBean.getCpuLoad();
            if (usage > 0) {
                return roundTwoDecimal(usage * 100);
            }
            double loadAverage = osBean.getSystemLoadAverage();
            int processors = Math.max(osBean.getAvailableProcessors(), 1);
            if (loadAverage > 0) {
                return roundTwoDecimal(Math.min(100D, loadAverage * 100D / processors));
            }
            return 0D;
        } catch (Exception ex) {
            log.warn("读取 CPU 使用率失败", ex);
            return 0D;
        }
    }

    private Double resolveMemoryUsage() {
        try {
            long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
            long max = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
            if (max <= 0) {
                return 0D;
            }
            return roundTwoDecimal(used * 100D / max);
        } catch (Exception ex) {
            log.warn("读取内存使用率失败", ex);
            return 0D;
        }
    }

    private Double resolveDiskUsage() {
        try {
            Path path = Path.of(workspaceRootPath);
            if (!Files.exists(path)) {
                path = Path.of(System.getProperty("user.home"));
            }
            FileStore store = Files.getFileStore(path);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            if (total <= 0) {
                return 0D;
            }
            return roundTwoDecimal((total - usable) * 100D / total);
        } catch (Exception ex) {
            log.warn("读取磁盘使用率失败", ex);
            return 0D;
        }
    }

    private String checkPostgreSql() {
        try {
            agentMapper.selectCount(new LambdaQueryWrapper<Agent>().last("LIMIT 1"));
            return "up";
        } catch (Exception ex) {
            log.warn("PostgreSQL 健康检查失败", ex);
            return "down";
        }
    }

    private String checkRedis() {
        try {
            String key = "sf:health:redis:" + LocalDate.now() + ":" + LocalTime.now().getMinute();
            stringRedisTemplate.opsForValue().setIfAbsent(key, "1", java.time.Duration.ofMinutes(1));
            stringRedisTemplate.hasKey(key);
            return "up";
        } catch (Exception ex) {
            log.warn("Redis 健康检查失败", ex);
            return "down";
        }
    }

    private String checkRabbitMq() {
        try {
            Boolean result = rabbitTemplate.execute(channel -> channel.isOpen());
            return Boolean.TRUE.equals(result) ? "up" : "down";
        } catch (Exception ex) {
            log.warn("RabbitMQ 健康检查失败", ex);
            return "down";
        }
    }

    private String checkClickHouse() {
        ClickHouseAnalyticsService clickHouseAnalyticsService = clickHouseAnalyticsServiceProvider.getIfAvailable();
        if (clickHouseAnalyticsService == null) {
            return "disabled";
        }
        try {
            return clickHouseAnalyticsService.isAvailable() ? "up" : "down";
        } catch (Exception ex) {
            log.warn("ClickHouse 健康检查失败", ex);
            return "down";
        }
    }

    private String resolveOverallStatus(Map<String, String> serviceStatus, SystemHealthVO health) {
        boolean hasDownService = serviceStatus.values().stream().anyMatch("down"::equalsIgnoreCase);
        if (hasDownService) {
            return "degraded";
        }
        if ((health.getCpuUsage() != null && health.getCpuUsage() >= 90)
                || (health.getMemoryUsage() != null && health.getMemoryUsage() >= 90)
                || (health.getDiskUsage() != null && health.getDiskUsage() >= 90)) {
            return "degraded";
        }
        return "healthy";
    }

    private double roundTwoDecimal(double value) {
        return Math.round(value * 100D) / 100D;
    }

    private String defaultText(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
