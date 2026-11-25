package com.schemaplexai.web.config;

import com.schemaplexai.common.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalExecutionIsolationTest {

    @Test
    void saturatedEvaluationsYieldIndependentlyOfOnlineExecution() throws Exception {
        var config = new AgentAsyncConfig();
        var decorator = config.securityContextTaskDecorator();
        var online = (ThreadPoolTaskExecutor) config.agentExecutorPool(decorator);
        var coordinators = (ThreadPoolTaskExecutor) config.evalCoordinatorPool(decorator);
        var items = (ThreadPoolTaskExecutor) config.evalItemPool(decorator);
        var workers = (ThreadPoolTaskExecutor) config.evalAgentExecutorPool(decorator);
        var blocked = new CountDownLatch(workers.getMaxPoolSize());
        var release = new CountDownLatch(1);
        List<CompletableFuture<String>> evaluations = new ArrayList<>();
        int capacity = coordinators.getMaxPoolSize() + coordinators.getThreadPoolExecutor().getQueue().remainingCapacity();
        try {
            SecurityUtil.setCurrentTenantId("隔离评估租户");
            for (int i = 0; i < capacity; i++) {
                evaluations.add(CompletableFuture.supplyAsync(() -> {
                    var work = IntStream.range(0, 2).mapToObj(unused ->
                            CompletableFuture.supplyAsync(() -> CompletableFuture.supplyAsync(() -> {
                                blocked.countDown();
                                try {
                                    if (!release.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("评估等待超时");
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IllegalStateException(e);
                                }
                                return SecurityUtil.getCurrentTenantId();
                            }, workers).join(), items)).toList();
                    return work.get(0).join() + work.get(1).join();
                }, coordinators));
            }
            assertTrue(blocked.await(4, TimeUnit.SECONDS));
            assertTrue(capacity > online.getCorePoolSize());
            assertThrows(RejectedExecutionException.class, () -> coordinators.execute(() -> {}));
            SecurityUtil.setCurrentTenantId("在线租户");
            var onlineResult = CompletableFuture.supplyAsync(SecurityUtil::getCurrentTenantId, online);
            assertEquals("在线租户", onlineResult.get(3, TimeUnit.SECONDS));
            release.countDown();
            for (var result : evaluations) {
                assertEquals("隔离评估租户隔离评估租户", result.get(5, TimeUnit.SECONDS));
            }
        } finally {
            release.countDown();
            SecurityUtil.clear();
            List.of(online, coordinators, items, workers).forEach(ThreadPoolTaskExecutor::shutdown);
        }
    }
}
