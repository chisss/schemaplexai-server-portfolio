package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.agent.hook.AgentHookExecutor;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import com.schemaplexai.service.ai.ModelMetricsTracker;
import com.schemaplexai.service.monitor.AgentTraceService;
import com.schemaplexai.service.monitor.RouteAnalysisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@EnabledIfEnvironmentVariable(named = "AGENT_PERMIT_TEST_REDIS_PORT", matches = "[0-9]+")
class AgentModelPermitTest {
    @Test
    void sharedWaitDoesNotOccupyLocalPermitsAndTicketsExpirePromptly() throws Exception {
        var connection = new LettuceConnectionFactory("127.0.0.1",
                Integer.parseInt(System.getenv("AGENT_PERMIT_TEST_REDIS_PORT")));
        connection.afterPropertiesSet();
        var redis = new StringRedisTemplate(connection);
        var first = invoker(redis);
        var second = invoker(redis);
        List<Runnable> releases = new ArrayList<>();
        String key = "exec:model:permits:evaluation";
        try {
            for (int index = 0; index < 4; index++) releases.add(first.acquirePermit(budget(10_000)));
            assertThat(redis.opsForZSet().size(key)).isEqualTo(4);
            assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(30_000L, 90_000L);
            var local = (Semaphore) ReflectionTestUtils.getField(second, "evaluationPermits");
            long before = System.currentTimeMillis();
            var waiting = new FutureTask<>(() -> second.acquirePermit(budget(750)));
            Thread.startVirtualThread(waiting);
            while (System.currentTimeMillis() < before + 100) Thread.sleep(5);
            assertThat(local.availablePermits()).isEqualTo(4);
            assertThatThrownBy(() -> waiting.get(3, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AgentExecutionBudget.BudgetExceededException.class);
            assertThat(local.availablePermits()).isEqualTo(4);
            releases.removeLast().run();
            second.acquirePermit(budget(2_000)).run();
        } finally {
            releases.forEach(Runnable::run);
            first.shutdown();
            second.shutdown();
            connection.destroy();
        }
    }

    private AgentExecutionBudget budget(long millis) {
        long now = System.currentTimeMillis();
        return new AgentExecutionBudget(now + millis, 5000, 0, now, new AgentLoopState(),
                () -> false, null, null, true);
    }

    private AgentModelInvoker invoker(StringRedisTemplate redis) {
        var invoker = new AgentModelInvoker(mock(AgentHookExecutor.class), mock(ModelMetricsTracker.class),
                mock(RouteAnalysisService.class), mock(AgentTraceService.class), mock(LangChain4jModelFactory.class));
        invoker.setCooldownRedis(redis);
        return invoker;
    }
}
