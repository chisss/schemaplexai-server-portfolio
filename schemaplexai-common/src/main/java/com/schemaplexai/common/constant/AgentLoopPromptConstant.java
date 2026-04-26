package com.schemaplexai.common.constant;

/**
 * Agent 执行循环提示词常量
 *
 * <p>所有硬编码提示词集中在此处管理，便于后续国际化（i18n）改造。
 * 国际化改造时，将此类替换为 MessageSource 注入即可，调用方无需修改。</p>
 *
 * <p>TODO: i18n 改造时，将常量替换为 MessageSource.getMessage(key, args, locale) 调用</p>
 */
public final class AgentLoopPromptConstant {

    private AgentLoopPromptConstant() {}

    /**
     * 强制收敛提示词：工具与轮次预算耗尽时，要求模型直接输出最终结果
     */
    public static final String FORCE_COMPLETION =
            "工具与轮次预算已达到上限。不要继续调用任何工具，请基于当前已获得的信息直接输出最终结果。"
            + "最终内容只保留已确认事实、结论、关键证据、行动方案与风险边界。"
            + "如果是客户交付类文档，不要输出内部仓库审计标题、缺失占位语或回填说明。"
            + "如果个别细节无法确认，请放入\"风险与待确认事项\"，不要猜测，并直接输出结构化 Markdown。";

    /**
     * 非预期工具调用恢复提示词：禁用工具轮次中模型仍请求工具时使用
     */
    public static final String NO_TOOL_CALL_RECOVERY =
            "当前轮次禁止继续调用任何工具，但上一条响应仍在请求工具，因此该响应无效。"
            + "请不要继续调用工具，直接基于现有上下文输出完整最终答案。"
            + "如果信息不足，请明确写\"待确认\"；若为客户交付类文档，不要输出内部占位语。";

    /**
     * 输出截断续写提示词：模型输出被 maxTokens 截断时使用
     */
    public static final String CONTINUE_TRUNCATED =
            "请继续完成上一条消息中被截断的内容，直接输出完整内容，不需要解释。";

    /**
     * 工具结果截断提示词：工具输出过长时追加的截断说明
     */
    public static final String TOOL_RESULT_TRUNCATION_NOTICE =
            "\n...[工具输出过长，已截断。请缩小查询范围、限制目录或改用更精确的参数后重试]";

    /**
     * 模型返回空响应且无历史证据时的错误信息
     */
    public static final String EMPTY_AI_RESPONSE_ERROR =
            "模型返回空响应，且缺少可用于收敛的历史证据";
}
