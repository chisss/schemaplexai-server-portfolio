package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.WorkspaceScope;
import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;
import java.util.Set;

/**
 * Agent 执行沙箱策略
 */
@Data
@Builder
public class SandboxPolicy {

    private String tenantId;
    private String agentId;
    private String executionId;
    private WorkspaceScope workspaceScope;
    private Set<String> allowedToolCodes;
    private Set<Path> allowedPathPrefixes;
    private int maxExecutionMinutes;
    private long maxTokenBudget;
    private boolean networkEgressEnabled;
}
