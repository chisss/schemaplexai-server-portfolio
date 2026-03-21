package com.schemaplexai.web.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Agent 异步执行线程池配置
 * 供 AgentExecutionEngine 的 @Async("agentExecutorPool") 使用
 */
@Configuration
public class AgentAsyncConfig {

    @Bean("agentExecutorPool")
    public Executor agentExecutorPool() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("agent-exec-");
        executor.setKeepAliveSeconds(60);
        executor.initialize();
        return executor;
    }
}
