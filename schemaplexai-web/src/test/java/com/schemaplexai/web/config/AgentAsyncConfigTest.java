package com.schemaplexai.web.config;

import com.schemaplexai.common.util.SecurityUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAsyncConfigTest {

    @Test
    void shouldKeepMembersAndSoloProgressingUnderTeamLoad() throws Exception {
        try (var context = new AnnotationConfigApplicationContext(AgentAsyncConfig.class)) {
            Executor teamPool = context.getBean("teamOrchestrationPool", Executor.class);
            Executor agentPool = context.getBean("agentExecutorPool", Executor.class);
            SecurityUtil.setCurrentTenantId("test-tenant");
            List<CompletableFuture<String>> parents = new ArrayList<>();
            CountDownLatch parentStarted = new CountDownLatch(8);
            CompletableFuture<Void> startMembers = new CompletableFuture<>();
            for (int i = 0; i < 17; i++) {
                parents.add(CompletableFuture.supplyAsync(() -> {
                    assertThat(Thread.currentThread().getName()).startsWith("team-orchestrate-");
                    parentStarted.countDown();
                    startMembers.join();
                    return CompletableFuture.supplyAsync(() -> {
                        assertThat(Thread.currentThread().getName()).startsWith("agent-exec-");
                        return SecurityUtil.getCurrentTenantId();
                    }, agentPool).join();
                }, teamPool));
            }
            CompletableFuture<String> solo;
            try {
                assertThat(parentStarted.await(5, TimeUnit.SECONDS)).isTrue();
                solo = CompletableFuture.supplyAsync(SecurityUtil::getCurrentTenantId, agentPool);
            } finally {
                startMembers.complete(null);
            }
            CompletableFuture.allOf(parents.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertThat(parents).allSatisfy(parent -> assertThat(parent.join()).isEqualTo("test-tenant"));
            assertThat(solo.get(5, TimeUnit.SECONDS)).isEqualTo("test-tenant");
        } finally {
            SecurityUtil.clear();
        }
    }
}
