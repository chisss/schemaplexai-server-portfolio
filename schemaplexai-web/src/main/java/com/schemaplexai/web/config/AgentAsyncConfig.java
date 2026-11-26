package com.schemaplexai.web.config;

import com.schemaplexai.common.util.SecurityUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;

import java.util.concurrent.Executor;

/**
 * Agent 异步执行线程池配置
 * 供 AgentExecutionEngine 的 @Async("agentExecutorPool") 使用
 */
@Configuration
public class AgentAsyncConfig {

    @Bean
    public TaskDecorator securityContextTaskDecorator() {
        return runnable -> {
            String callerUserId = SecurityUtil.getCurrentUserId();
            String callerTenantId = SecurityUtil.getCurrentTenantId();
            String callerUsername = SecurityUtil.getCurrentUsername();
            return () -> {
                String originalUserId = SecurityUtil.getCurrentUserId();
                String originalTenantId = SecurityUtil.getCurrentTenantId();
                String originalUsername = SecurityUtil.getCurrentUsername();
                try {
                    SecurityUtil.clear();
                    if (StringUtils.hasText(callerUserId)) {
                        SecurityUtil.setCurrentUserId(callerUserId);
                    }
                    if (StringUtils.hasText(callerTenantId)) {
                        SecurityUtil.setCurrentTenantId(callerTenantId);
                    }
                    if (StringUtils.hasText(callerUsername)) {
                        SecurityUtil.setCurrentUsername(callerUsername);
                    }
                    runnable.run();
                } finally {
                    SecurityUtil.clear();
                    if (StringUtils.hasText(originalUserId)) {
                        SecurityUtil.setCurrentUserId(originalUserId);
                    }
                    if (StringUtils.hasText(originalTenantId)) {
                        SecurityUtil.setCurrentTenantId(originalTenantId);
                    }
                    if (StringUtils.hasText(originalUsername)) {
                        SecurityUtil.setCurrentUsername(originalUsername);
                    }
                }
            };
        };
    }

    @Bean("agentExecutorPool")
    public Executor agentExecutorPool(TaskDecorator securityContextTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(16);
        executor.setMaxPoolSize(48);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("agent-exec-");
        executor.setKeepAliveSeconds(60);
        executor.setTaskDecorator(securityContextTaskDecorator);
        executor.initialize();
        return executor;
    }

    @Bean("teamOrchestrationPool")
    public Executor teamOrchestrationPool(TaskDecorator securityContextTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("team-orchestrate-");
        executor.setKeepAliveSeconds(60);
        executor.setTaskDecorator(securityContextTaskDecorator);
        executor.initialize();
        return executor;
    }

    @Bean("evalCoordinatorPool")
    public Executor evalCoordinatorPool(TaskDecorator decorator) {
        return evalPool(decorator, "eval-coordinator-", 2, 64);
    }

    @Bean("queryPlanningPool")
    public Executor queryPlanningPool(TaskDecorator decorator) {
        return evalPool(decorator, "query-plan-", 6, 12);
    }

    @Bean("evalItemPool")
    public Executor evalItemPool(TaskDecorator decorator) {
        return evalPool(decorator, "eval-item-", 8, 16);
    }

    @Bean("evalAgentExecutorPool")
    public Executor evalAgentExecutorPool(TaskDecorator decorator) {
        return evalPool(decorator, "eval-agent-", 4, 8);
    }

    @Bean("evalTeamOrchestrationPool")
    public Executor evalTeamOrchestrationPool(TaskDecorator decorator) {
        return evalPool(decorator, "eval-team-", 2, 4);
    }

    private Executor evalPool(TaskDecorator decorator, String prefix, int capacity, int queueSize) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(capacity);
        executor.setMaxPoolSize(capacity);
        executor.setQueueCapacity(queueSize);
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(decorator);
        executor.initialize();
        return executor;
    }

    @Bean("toolParallelPool")
    public Executor toolParallelPool(TaskDecorator securityContextTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("tool-parallel-");
        executor.setKeepAliveSeconds(30);
        executor.setTaskDecorator(securityContextTaskDecorator);
        executor.initialize();
        return executor;
    }
}
