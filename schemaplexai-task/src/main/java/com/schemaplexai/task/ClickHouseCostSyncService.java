package com.schemaplexai.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.service.clickhouse.ClickHouseAnalyticsService;
import com.schemaplexai.service.clickhouse.ClickHouseProperties;
import com.schemaplexai.service.clickhouse.ClickHouseSyncCursor;
import com.schemaplexai.service.clickhouse.ClickHouseSyncCursorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ClickHouse 成本明细增量同步服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickHouseCostSyncService {

    private final AgentExecutionMapper agentExecutionMapper;
    private final AiModelMapper aiModelMapper;
    private final ObjectProvider<ClickHouseAnalyticsService> clickHouseAnalyticsServiceProvider;
    private final ClickHouseSyncCursorService clickHouseSyncCursorService;
    private final ClickHouseProperties clickHouseProperties;

    /**
     * 按最后同步游标增量同步 Token 消耗数据
     */
    public void syncIncrementalCostData() {
        ClickHouseAnalyticsService clickHouseAnalyticsService = clickHouseAnalyticsServiceProvider.getIfAvailable();
        if (clickHouseAnalyticsService == null) {
            log.info("ClickHouse 未启用，跳过成本明细同步");
            return;
        }

        ClickHouseSyncCursor cursor = resolveCostRecordCursor(clickHouseAnalyticsService);
        LocalDateTime watermark = LocalDateTime.now().minusSeconds(Math.max(clickHouseProperties.getSyncLagSeconds(), 0));
        int batchSize = Math.max(clickHouseProperties.getSyncBatchSize(), 1);

        List<AgentExecution> executions = agentExecutionMapper.selectList(
                new LambdaQueryWrapper<AgentExecution>()
                        .eq(AgentExecution::getStatus, AgentExecutionStatusEnum.COMPLETED.getCode())
                        .isNotNull(AgentExecution::getCompletedAt)
                        .le(AgentExecution::getCompletedAt, watermark)
                        .and(wrapper -> wrapper.gt(AgentExecution::getCompletedAt, cursor.cursorTime())
                                .or()
                                .eq(AgentExecution::getCompletedAt, cursor.cursorTime())
                                .gt(AgentExecution::getId, cursor.cursorId()))
                        .and(wrapper -> wrapper.gt(AgentExecution::getTokenInput, 0)
                                .or()
                                .gt(AgentExecution::getTokenOutput, 0))
                        .orderByAsc(AgentExecution::getCompletedAt, AgentExecution::getId)
                        .last("LIMIT " + batchSize)
        );

        if (executions.isEmpty()) {
            log.info("无待同步 ClickHouse 成本明细: cursorTime={}, cursorId={}, watermark={}",
                    cursor.cursorTime(), cursor.cursorId(), watermark);
            return;
        }

        Map<String, AiModel> modelMap = loadModelMap(executions);
        logCostSummary(executions, modelMap);

        int inserted = clickHouseAnalyticsService.syncCostRecords(executions, modelMap);
        AgentExecution lastExecution = executions.getLast();
        clickHouseSyncCursorService.saveCostRecordCursor(
                new ClickHouseSyncCursor(lastExecution.getCompletedAt(), lastExecution.getId())
        );
        log.info("ClickHouse 成本明细同步结果: inserted={}", inserted);
    }

    /**
     * 解析同步游标，首次启动时优先从 ClickHouse 已有数据恢复
     */
    private ClickHouseSyncCursor resolveCostRecordCursor(ClickHouseAnalyticsService clickHouseAnalyticsService) {
        ClickHouseSyncCursor cursor = clickHouseSyncCursorService.loadCostRecordCursor();
        if (!isInitialCursor(cursor)) {
            return cursor;
        }
        ClickHouseSyncCursor latestCursor = clickHouseAnalyticsService.loadLatestCostRecordCursor();
        if (latestCursor == null) {
            return cursor;
        }
        AgentExecution cursorExecution = agentExecutionMapper.selectById(latestCursor.cursorId());
        if (cursorExecution != null && cursorExecution.getCompletedAt() != null) {
            latestCursor = new ClickHouseSyncCursor(cursorExecution.getCompletedAt(), cursorExecution.getId());
        }
        clickHouseSyncCursorService.saveCostRecordCursor(latestCursor);
        log.info("从 ClickHouse 已有数据初始化成本同步游标: cursorTime={}, cursorId={}",
                latestCursor.cursorTime(), latestCursor.cursorId());
        return latestCursor;
    }

    private boolean isInitialCursor(ClickHouseSyncCursor cursor) {
        return cursor != null && Duration.between(LocalDateTime.of(1970, 1, 1, 0, 0), cursor.cursorTime()).isZero();
    }

    /**
     * 加载模型ID→模型实体的映射
     */
    private Map<String, AiModel> loadModelMap(List<AgentExecution> executions) {
        List<String> modelIds = executions.stream()
                .map(AgentExecution::getAiModel)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        return aiModelMapper.selectBatchIds(modelIds).stream()
                .collect(Collectors.toMap(AiModel::getId, Function.identity(), (a, b) -> a));
    }

    private void logCostSummary(List<AgentExecution> executions, Map<String, AiModel> modelMap) {
        long totalInputTokens = 0;
        long totalOutputTokens = 0;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (AgentExecution exec : executions) {
            long inputTokens = exec.getTokenInput() != null ? exec.getTokenInput() : 0L;
            long outputTokens = exec.getTokenOutput() != null ? exec.getTokenOutput() : 0L;
            totalInputTokens += inputTokens;
            totalOutputTokens += outputTokens;

            AiModel model = modelMap.get(exec.getAiModel());
            BigDecimal inputPrice = model != null && model.getInputPrice() != null ? model.getInputPrice() : BigDecimal.ZERO;
            BigDecimal outputPrice = model != null && model.getOutputPrice() != null ? model.getOutputPrice() : BigDecimal.ZERO;
            BigDecimal cost = inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                    .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                    .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
            totalCost = totalCost.add(cost);
        }

        log.info("ClickHouse 增量成本统计: executions={}, inputTokens={}, outputTokens={}, totalCost={}",
                executions.size(), totalInputTokens, totalOutputTokens,
                totalCost.setScale(4, RoundingMode.HALF_UP));
    }
}
