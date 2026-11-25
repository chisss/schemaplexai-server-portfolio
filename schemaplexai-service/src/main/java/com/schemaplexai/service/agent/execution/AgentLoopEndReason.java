package com.schemaplexai.service.agent.execution;

/** Agent 循环单次运行的结束原因，独立于业务状态。 */
public enum AgentLoopEndReason {
    MODEL_COMPLETED,
    ROUND_LIMIT,
    QUALITY_LIMIT,
    REPEATED_LOOP,
    EMPTY_RESPONSE,
    UNEXPECTED_TOOL,
    MODEL_ERROR,
    DEADLINE,
    TOKEN_BUDGET,
    SECURITY_PAUSED,
    SECURITY_BLOCKED,
    APPROVAL_REQUIRED,
    DESKTOP_VERIFICATION_MISSING,
    STOPPED,
    INVALID_INPUT,
    INTERNAL_ERROR
}
