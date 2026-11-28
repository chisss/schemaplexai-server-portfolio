package com.schemaplexai.web.config;

import com.schemaplexai.service.agent.tool.sandbox.CodeExecProperties;
import com.schemaplexai.service.agent.tool.sandbox.WasmSandboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class AgentToolWorkerHealth implements HealthIndicator {
    private final CodeExecProperties properties;
    private final WasmSandboxService sandboxService;

    @Override
    public Health health() {
        if (!properties.isEnabled()) return Health.up().build();
        if (properties.getWorkerUrl() == null && !properties.isRequireWorker()) return Health.up().build();
        return sandboxService.workerAvailable() ? Health.up().build() : Health.down().build();
    }
}
