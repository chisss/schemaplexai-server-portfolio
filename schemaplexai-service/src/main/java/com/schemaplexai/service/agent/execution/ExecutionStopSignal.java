package com.schemaplexai.service.agent.execution;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;

@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionStopSignal {
    private static final String PREFIX = "exec:stopped:";
    private final StringRedisTemplate redis;
    private final AgentExecutionMapper executions;
    private final Cache<String, Boolean> recentDatabaseChecks = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(5)).maximumSize(10_000).build();

    public boolean isStopped(String executionId) {
        try {
            if (Boolean.TRUE.equals(redis.hasKey(PREFIX + executionId))) return true;
        } catch (DataAccessException e) {
            log.debug("Redis 停止信号读取失败，回退数据库: executionId={}", executionId);
        }
        return Boolean.TRUE.equals(recentDatabaseChecks.get(executionId, id -> {
            var execution = executions.selectById(id);
            return execution != null && (AgentExecutionStatusEnum.STOPPED.getCode().equals(execution.getStatus())
                    || AgentExecutionStatusEnum.FAILED.getCode().equals(execution.getStatus())
                    && AgentExpiredRunMaintenance.FAILURE_REASON.equals(execution.getErrorMessage()));
        }));
    }

    public void publishAfterCommit(String executionId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(executionId);
                }
            });
            return;
        }
        publish(executionId);
    }

    private void publish(String executionId) {
        recentDatabaseChecks.put(executionId, true);
        try {
            redis.opsForValue().set(PREFIX + executionId, "1", Duration.ofHours(24));
        } catch (DataAccessException e) {
            log.warn("Redis 停止信号写入失败: executionId={}", executionId);
        }
    }
}
