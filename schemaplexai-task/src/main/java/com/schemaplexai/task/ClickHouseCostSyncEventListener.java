package com.schemaplexai.task;

import com.schemaplexai.service.agent.execution.AgentExecutionCompletedEvent;
import com.schemaplexai.service.clickhouse.ClickHouseProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Agent 执行完成后触发 ClickHouse 成本同步
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickHouseCostSyncEventListener {

    private final ClickHouseCostSyncService clickHouseCostSyncService;
    private final ClickHouseProperties clickHouseProperties;

    /**
     * 事务提交后异步触发一次增量同步，避免等待下一次整点任务
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAgentExecutionCompleted(AgentExecutionCompletedEvent event) {
        try {
            int lagSeconds = Math.max(clickHouseProperties.getSyncLagSeconds(), 0);
            if (lagSeconds > 0) {
                Thread.sleep((lagSeconds + 1L) * 1000L);
            }
            log.info("Agent 执行完成后触发 ClickHouse 成本同步: executionId={}", event.executionId());
            clickHouseCostSyncService.syncIncrementalCostData();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("ClickHouse 成本同步触发线程被中断: executionId={}", event.executionId(), ex);
        } catch (Exception ex) {
            log.warn("Agent 执行完成后触发 ClickHouse 成本同步失败: executionId={}", event.executionId(), ex);
        }
    }
}
