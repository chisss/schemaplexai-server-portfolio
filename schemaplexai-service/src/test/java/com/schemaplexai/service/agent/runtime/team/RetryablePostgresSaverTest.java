package com.schemaplexai.service.agent.runtime.team;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计 LA-09：checkpoint 写入重试的故障注入测试。
 */
class RetryablePostgresSaverTest {

    @Test
    void shouldRetryTransientFailureThenSucceed() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        RetryablePostgresSaver.CheckpointWrite action = () -> {
            if (attempts.incrementAndGet() <= 2) {
                throw new SQLTransientConnectionException("connection lost");
            }
        };
        RetryablePostgresSaver.runWithRetry(action);
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    void shouldNotRetryPermanentError() {
        AtomicInteger attempts = new AtomicInteger();
        RetryablePostgresSaver.CheckpointWrite action = () -> {
            attempts.incrementAndGet();
            throw new SQLIntegrityConstraintViolationException("duplicate key");
        };
        assertThatThrownBy(() -> RetryablePostgresSaver.runWithRetry(action))
                .isInstanceOf(SQLException.class);
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void shouldGiveUpAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();
        RetryablePostgresSaver.CheckpointWrite action = () -> {
            attempts.incrementAndGet();
            throw new SQLTransientConnectionException("still down");
        };
        assertThatThrownBy(() -> RetryablePostgresSaver.runWithRetry(action))
                .isInstanceOf(SQLException.class);
        assertThat(attempts.get()).isEqualTo(3);
    }
}
