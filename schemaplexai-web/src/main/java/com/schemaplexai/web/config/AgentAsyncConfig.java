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
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agent-exec-");
        executor.setKeepAliveSeconds(60);
        executor.setTaskDecorator(securityContextTaskDecorator);
        executor.initialize();
        return executor;
    }
}
