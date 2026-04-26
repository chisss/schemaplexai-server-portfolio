package com.schemaplexai.service.clickhouse;

import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ClickHouse 分析数据写入服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "clickhouse", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ClickHouseAnalyticsService {

    private static final String COST_INSERT_SQL = """
            INSERT INTO schemaplexai.sf_cost_record
            (event_time, tenant_id, project_id, user_id, agent_id, model_id, model_name, task_type,
             token_input, token_output, cost_amount, execution_id, spec_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final DateTimeFormatter CLICKHOUSE_DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ClickHouseProperties properties;
    private JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initJdbcTemplate() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        dataSource.setUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        if (StringUtils.hasText(properties.getPassword())) {
            dataSource.setPassword(properties.getPassword());
        }
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * 初始化分析库表结构，兼容已有数据卷不会重复执行 entrypoint SQL 的场景
     */
    public void ensureSchema() {
        jdbcTemplate.execute("CREATE DATABASE IF NOT EXISTS schemaplexai");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schemaplexai.sf_cost_record (
                    event_time DateTime DEFAULT now(),
                    tenant_id String,
                    project_id Nullable(String),
                    user_id String,
                    agent_id String,
                    model_id String,
                    model_name String,
                    task_type String,
                    token_input UInt64 DEFAULT 0,
                    token_output UInt64 DEFAULT 0,
                    cost_amount Decimal64(6) DEFAULT 0,
                    execution_id String,
                    spec_id Nullable(String),
                    inserted_at DateTime DEFAULT now()
                ) ENGINE = ReplacingMergeTree(inserted_at)
                PARTITION BY toYYYYMM(event_time)
                ORDER BY (tenant_id, execution_id)
                TTL event_time + INTERVAL 365 DAY
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS schemaplexai.sf_agent_metric (
                    event_time DateTime DEFAULT now(),
                    tenant_id String,
                    agent_id String,
                    agent_name String,
                    execution_count UInt64 DEFAULT 0,
                    success_count UInt64 DEFAULT 0,
                    fail_count UInt64 DEFAULT 0,
                    avg_duration_ms UInt64 DEFAULT 0,
                    total_tokens UInt64 DEFAULT 0,
                    total_cost Decimal64(6) DEFAULT 0
                ) ENGINE = SummingMergeTree()
                PARTITION BY toYYYYMM(event_time)
                ORDER BY (tenant_id, agent_id, event_time)
                """);
        jdbcTemplate.execute("""
                CREATE MATERIALIZED VIEW IF NOT EXISTS schemaplexai.mv_daily_cost
                ENGINE = SummingMergeTree()
                PARTITION BY toYYYYMM(day)
                ORDER BY (tenant_id, day, model_name)
                AS SELECT
                    toDate(event_time) AS day,
                    tenant_id,
                    model_name,
                    sum(token_input) AS total_token_input,
                    sum(token_output) AS total_token_output,
                    sum(cost_amount) AS total_cost,
                    count() AS call_count
                FROM schemaplexai.sf_cost_record
                GROUP BY day, tenant_id, model_name
                """);
    }

    /**
     * 写入执行成本明细，已存在的执行记录会跳过，避免定时任务重复插入
     */
    public int syncCostRecords(List<AgentExecution> executions, Map<String, AiModel> modelMap) {
        if (executions == null || executions.isEmpty()) {
            return 0;
        }
        ensureSchema();
        List<AgentExecution> candidates = executions.stream()
                .filter(this::hasTokenUsage)
                .filter(execution -> StringUtils.hasText(execution.getId()))
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }

        Set<String> existingExecutionIds = loadExistingExecutionIds(candidates);
        List<Object[]> rows = new ArrayList<>();
        for (AgentExecution execution : candidates) {
            if (existingExecutionIds.contains(execution.getId())) {
                continue;
            }
            AiModel model = modelMap.get(execution.getAiModel());
            rows.add(toCostRecordRow(execution, model));
        }
        if (rows.isEmpty()) {
            return 0;
        }
        int[] results = jdbcTemplate.batchUpdate(COST_INSERT_SQL, rows);
        int inserted = results.length;
        log.info("ClickHouse 成本明细同步完成: candidates={}, inserted={}", candidates.size(), inserted);
        return inserted;
    }

    /**
     * ClickHouse 健康检查
     */
    public boolean isAvailable() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        return result != null && result == 1;
    }

    /**
     * 从 ClickHouse 已有成本明细恢复最新游标
     */
    public ClickHouseSyncCursor loadLatestCostRecordCursor() {
        ensureSchema();
        List<ClickHouseSyncCursor> cursors = jdbcTemplate.query(
                """
                        SELECT event_time, execution_id
                        FROM schemaplexai.sf_cost_record
                        ORDER BY event_time DESC, execution_id DESC
                        LIMIT 1
                        """,
                (rs, rowNum) -> new ClickHouseSyncCursor(
                        rs.getTimestamp("event_time").toLocalDateTime(),
                        rs.getString("execution_id")
                )
        );
        return cursors.isEmpty() ? null : cursors.getFirst();
    }

    private Set<String> loadExistingExecutionIds(List<AgentExecution> executions) {
        List<String> executionIds = executions.stream()
                .map(AgentExecution::getId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (executionIds.isEmpty()) {
            return Set.of();
        }
        String placeholders = executionIds.stream().map(id -> "?").collect(Collectors.joining(","));
        List<String> exists = jdbcTemplate.queryForList(
                "SELECT execution_id FROM schemaplexai.sf_cost_record WHERE execution_id IN (" + placeholders + ")",
                String.class,
                executionIds.toArray()
        );
        return new HashSet<>(exists);
    }

    private Object[] toCostRecordRow(AgentExecution execution, AiModel model) {
        long inputTokens = execution.getTokenInput() != null ? execution.getTokenInput() : 0L;
        long outputTokens = execution.getTokenOutput() != null ? execution.getTokenOutput() : 0L;
        BigDecimal cost = calculateCost(model, inputTokens, outputTokens);
        LocalDateTime eventTime = execution.getCompletedAt() != null
                ? execution.getCompletedAt()
                : defaultTime(execution.getCreatedAt());
        String modelId = model != null && StringUtils.hasText(model.getId()) ? model.getId() : defaultText(execution.getAiModel());
        String modelName = model != null && StringUtils.hasText(model.getName()) ? model.getName() : defaultText(execution.getAiModel());
        return new Object[]{
                eventTime.format(CLICKHOUSE_DATETIME_FORMATTER),
                defaultText(execution.getTenantId()),
                null,
                defaultText(execution.getCreatedBy()),
                defaultText(execution.getAgentId()),
                modelId,
                modelName,
                defaultText(execution.getExecutionMode()),
                inputTokens,
                outputTokens,
                cost,
                execution.getId(),
                execution.getSpecId()
        };
    }

    private BigDecimal calculateCost(AiModel model, long inputTokens, long outputTokens) {
        BigDecimal inputPrice = model != null && model.getInputPrice() != null ? model.getInputPrice() : BigDecimal.ZERO;
        BigDecimal outputPrice = model != null && model.getOutputPrice() != null ? model.getOutputPrice() : BigDecimal.ZERO;
        return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    private boolean hasTokenUsage(AgentExecution execution) {
        if (execution == null || !AgentExecutionStatusEnum.COMPLETED.getCode().equals(execution.getStatus())) {
            return false;
        }
        long inputTokens = execution.getTokenInput() != null ? execution.getTokenInput() : 0L;
        long outputTokens = execution.getTokenOutput() != null ? execution.getTokenOutput() : 0L;
        return inputTokens > 0 || outputTokens > 0;
    }

    private LocalDateTime defaultTime(LocalDateTime time) {
        return time != null ? time : LocalDateTime.now();
    }

    private String defaultText(String value) {
        return StringUtils.hasText(value) ? value : "";
    }
}
