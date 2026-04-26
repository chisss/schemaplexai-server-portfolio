package com.schemaplexai.service.agent.hook;

/**
 * Agent Hook 接口
 *
 * <p>实现此接口并标注 {@code @Component} 即可自动注册为内置 Hook。
 * 返回 {@code false} 表示阻止后续执行（仅对 BEFORE_* 类型有效）。</p>
 */
public interface AgentHook {

    /** Hook 名称（用于日志和管理） */
    String name();

    /** 支持的 Hook 类型 */
    AgentHookType type();

    /** 排序权重，数值越小越先执行（默认 100） */
    default int order() { return 100; }

    /**
     * 执行 Hook 逻辑
     *
     * @param context Hook 上下文
     * @return true 继续执行，false 阻止后续（仅 BEFORE_* 有效）
     */
    boolean execute(AgentHookContext context);
}
