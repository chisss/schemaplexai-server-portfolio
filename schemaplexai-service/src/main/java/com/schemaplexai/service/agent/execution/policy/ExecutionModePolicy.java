package com.schemaplexai.service.agent.execution.policy;

import com.schemaplexai.common.enums.ExecutionModeEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 执行模式运行时策略
 */
@Data
@Builder
public class ExecutionModePolicy {

    private ExecutionModeEnum mode;
    private boolean allowReadTools;
    private boolean allowWriteTools;
    private boolean requireApprovalForWrite;
    private boolean requireApprovalForDestructive;
    private boolean allowProgressiveTrust;
    private boolean suggestOnly;
    private boolean applyToNonSystemAgent;
    private Set<ToolIoTypeEnum> directlyExecutableIoTypes;
}
