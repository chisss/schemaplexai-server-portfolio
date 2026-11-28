package com.schemaplexai.web.config;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 使用标准 OTEL 环境变量连接部署环境的收集器。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "schemaplexai.agent.otel.enabled", havingValue = "true")
class AgentOpenTelemetryConfig {

    @Bean(destroyMethod = "close")
    SdkTracerProvider agentTracerProvider() {
        return AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook()
                .setResultAsGlobal()
                .build().getOpenTelemetrySdk().getSdkTracerProvider();
    }
}
