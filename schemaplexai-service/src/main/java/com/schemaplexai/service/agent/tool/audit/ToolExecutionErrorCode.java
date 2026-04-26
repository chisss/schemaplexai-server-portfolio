package com.schemaplexai.service.agent.tool.audit;

/**
 * 工具执行错误码
 */
public final class ToolExecutionErrorCode {

    public static final String MISSING_WORKDIR = "MISSING_WORKDIR";
    public static final String PATH_NOT_FOUND = "PATH_NOT_FOUND";
    public static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    public static final String COMMAND_FAILED = "COMMAND_FAILED";
    public static final String SKILL_NOT_INSTALLED = "SKILL_NOT_INSTALLED";
    public static final String SANDBOX_VIOLATION = "SANDBOX_VIOLATION";
    public static final String UNKNOWN_ERROR = "UNKNOWN_ERROR";

    private ToolExecutionErrorCode() {
    }
}
