package com.schemaplexai.common.constant;

import java.util.regex.Pattern;

/**
 * Agent 执行引擎正则表达式常量
 * <p>统一管理 AgentExecutionEngine 中使用的所有正则模式，避免分散定义</p>
 */
public final class AgentPatternConstant {

    private AgentPatternConstant() {}

    /** 内部工具调用追踪标识，如 call_function_xxx */
    public static final Pattern INTERNAL_TOOL_TRACE = Pattern.compile("\\bcall_function_[A-Za-z0-9_]+\\b");

    /** 内部工具名称标识，如 sys.xxx */
    public static final Pattern INTERNAL_TOOL_NAME = Pattern.compile("\\bsys\\.[A-Za-z0-9_]+\\b");

    /** XML tool_call 块 */
    public static final Pattern XML_TOOL_CALL_BLOCK = Pattern.compile("(?is)<[A-Za-z0-9_:-]*tool_call>.*?</[A-Za-z0-9_:-]*tool_call>");

    /** XML invoke 块 */
    public static final Pattern XML_TOOL_INVOKE_BLOCK = Pattern.compile("(?is)<invoke\\b[^>]*>.*?</invoke>");

    /** XML invoke/parameter 标签 */
    public static final Pattern XML_TOOL_TAG = Pattern.compile("(?is)</?(?:invoke|parameter)\\b[^>]*>");

    /** 占位符相对路径，如 .../foo/Bar.java */
    public static final Pattern PLACEHOLDER_PATH = Pattern.compile(
            "(?<![A-Za-z0-9._/-])(\\.\\.\\./[A-Za-z0-9._/-]+\\.(?:java|kt|xml|sql|md|tsx?|jsx?|json|ya?ml))(?![A-Za-z0-9._/-])",
            Pattern.CASE_INSENSITIVE
    );

    /** 绝对文件路径，如 /com/foo/Bar.java */
    public static final Pattern ABSOLUTE_FILE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9._/-])((?:/[A-Za-z0-9._-]+)+\\.(?:java|kt|xml|sql|md|tsx?|jsx?|json|ya?ml))(?![A-Za-z0-9._/-])",
            Pattern.CASE_INSENSITIVE
    );

    /** 绝对工作区路径，如 /Users/xxx 或 /home/xxx */
    public static final Pattern ABSOLUTE_WORKSPACE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9._/-])((?:/Users|/home)/[^\\s<`]+)",
            Pattern.CASE_INSENSITIVE
    );

    /** 工作区相对文件引用，如 src/main/java/Foo.java */
    public static final Pattern WORKSPACE_FILE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9._/-])([A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)+\\.(?:java|kt|xml|sql|md|tsx?|jsx?|json|ya?ml))(?![A-Za-z0-9._/-])",
            Pattern.CASE_INSENSITIVE
    );

    /** 无法交付类表述，如“无法完成”“请人工补充输入” */
    public static final Pattern INSUFFICIENT_DELIVERABLE = Pattern.compile(
            "(无法完成(?:此任务)?|请人工补充输入|缺少必要输入|未成功读取|无法(?:直接)?读取(?:该)?文件|无法基于事实(?:内容)?(?:完成|输出)|待人工补充)",
            Pattern.CASE_INSENSITIVE
    );
}
