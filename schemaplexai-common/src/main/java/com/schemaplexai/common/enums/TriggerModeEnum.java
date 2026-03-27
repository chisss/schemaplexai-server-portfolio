package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 质量任务触发模式枚举
 */
@Getter
@AllArgsConstructor
public enum TriggerModeEnum {

    MANUAL("manual", "手动触发"),
    AGENT_EXECUTION("agent_execution", "Agent执行触发"),
    WORKFLOW_NODE("workflow_node", "工作流节点触发"),
    SCHEDULED("scheduled", "定时触发"),
    EVENT("event", "事件触发");

    private final String code;
    private final String description;
}
