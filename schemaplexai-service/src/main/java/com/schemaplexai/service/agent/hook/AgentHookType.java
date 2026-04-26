package com.schemaplexai.service.agent.hook;

/**
 * Agent 执行 Hook 类型枚举（参照 Gemini CLI HookSystem）
 */
public enum AgentHookType {
    /** 模型调用前：可修改请求、注入额外上下文 */
    BEFORE_MODEL_CALL,
    /** 模型响应后：可修改响应内容、记录指标 */
    AFTER_MODEL_CALL,
    /** 工具执行前：可阻止执行（返回 false）、记录审计 */
    BEFORE_TOOL_EXECUTE,
    /** 工具执行后：可修改结果、触发后处理 */
    AFTER_TOOL_EXECUTE,
    /** 循环完成后：触发通知、记录统计 */
    ON_LOOP_COMPLETE
}
