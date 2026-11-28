package com.schemaplexai.service.agent.runtime.team;

import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;

import java.sql.SQLException;
import java.sql.SQLNonTransientException;
import java.util.LinkedList;

/**
 * 带写重试的 PostgresSaver（审计 LA-09）。
 *
 * <p>checkpoint 写入（insertedCheckpoint/updatedCheckpoint）遇到 PG 短暂抖动（主备切换、连接池抖动）时，
 * 指数退避重试最多 3 次，防止 checkpoint 写失败直接中断整个 Team 执行。</p>
 *
 * <p>实现说明：{@link PostgresSaver#put} 为 final 无法整体覆写，故覆写 protected 的
 * {@code insertedCheckpoint}/{@code updatedCheckpoint}。二者各自开启独立事务（execTransaction），
 * 失败即回滚，重试等价于一次全新事务，安全且幂等。仅对非 SQLNonTransientException 的 SQLException 重试，
 * 永久性错误（约束冲突、语法错误等）立即抛出不重试。</p>
 */
@Slf4j
public class RetryablePostgresSaver extends PostgresSaver {

    private static final int MAX_WRITE_ATTEMPTS = 3;
    private static final long RETRY_BASE_MILLIS = 200L;

    public static class Builder extends PostgresSaver.Builder {
        @Override
        public RetryablePostgresSaver build() throws Exception {
            return new RetryablePostgresSaver(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    protected RetryablePostgresSaver(Builder builder) throws Exception {
        super(builder);
    }

    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                      Checkpoint checkpoint) throws Exception {
        runWithRetry(() -> super.insertedCheckpoint(config, checkpoints, checkpoint));
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) throws Exception {
        runWithRetry(() -> super.updatedCheckpoint(config, checkpoints, checkpoint));
    }

    /**
     * 指数退避重试写入（审计 LA-09）：对非 SQLNonTransientException 的 SQLException 重试最多 {@link #MAX_WRITE_ATTEMPTS} 次，
     * 永久性错误立即抛出。包级静态以便故障注入测试。
     */
    static void runWithRetry(CheckpointWrite action) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                action.run();
                return;
            } catch (SQLException e) {
                if (e instanceof SQLNonTransientException || attempt >= MAX_WRITE_ATTEMPTS) {
                    throw e;
                }
                long backoffMillis = RETRY_BASE_MILLIS * (1L << (attempt - 1));
                log.warn("Checkpoint 写入失败，{}ms 后进行第 {} 次重试: {}", backoffMillis, attempt, e.getMessage());
                try {
                    Thread.sleep(backoffMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @FunctionalInterface
    interface CheckpointWrite {
        void run() throws Exception;
    }
}
