package com.schemaplexai.service.agent.tool.audit;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 工具执行失败分类器
 */
@Component
public class ToolExecutionClassifier {

    public static final String RECOVERABLE_TOOL_EXPLORATION = "RECOVERABLE_TOOL_EXPLORATION";
    public static final String BLOCKING_ERROR = "BLOCKING_ERROR";
    public static final String UNKNOWN = "UNKNOWN";

    public ToolExecutionClassification classify(String errorCode) {
        String normalized = StringUtils.hasText(errorCode) ? errorCode.trim().toUpperCase() : ToolExecutionErrorCode.UNKNOWN_ERROR;
        return switch (normalized) {
            case ToolExecutionErrorCode.MISSING_WORKDIR,
                    ToolExecutionErrorCode.PATH_NOT_FOUND,
                    ToolExecutionErrorCode.INVALID_ARGUMENT,
                    ToolExecutionErrorCode.COMMAND_FAILED ->
                    new ToolExecutionClassification(normalized, RECOVERABLE_TOOL_EXPLORATION, true);
            case ToolExecutionErrorCode.SKILL_NOT_INSTALLED,
                    ToolExecutionErrorCode.SANDBOX_VIOLATION ->
                    new ToolExecutionClassification(normalized, BLOCKING_ERROR, false);
            default -> new ToolExecutionClassification(ToolExecutionErrorCode.UNKNOWN_ERROR, UNKNOWN, false);
        };
    }

    public ToolExecutionClassification classifyMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return classify(ToolExecutionErrorCode.UNKNOWN_ERROR);
        }
        String normalized = message.toLowerCase();
        if (normalized.contains("缺少 workdir")) {
            return classify(ToolExecutionErrorCode.MISSING_WORKDIR);
        }
        if (normalized.contains("no such file") || normalized.contains("not found") || normalized.contains("不存在")) {
            return classify(ToolExecutionErrorCode.PATH_NOT_FOUND);
        }
        if (normalized.contains("参数校验失败") || normalized.contains("invalid argument")) {
            return classify(ToolExecutionErrorCode.INVALID_ARGUMENT);
        }
        if (normalized.contains("未安装") || normalized.contains("无访问权限")) {
            return classify(ToolExecutionErrorCode.SKILL_NOT_INSTALLED);
        }
        if (normalized.contains("沙箱") || normalized.contains("越权") || normalized.contains("超出沙箱")) {
            return classify(ToolExecutionErrorCode.SANDBOX_VIOLATION);
        }
        if (normalized.contains("退出码") || normalized.contains("timeout") || normalized.contains("超时")) {
            return classify(ToolExecutionErrorCode.COMMAND_FAILED);
        }
        return classify(ToolExecutionErrorCode.UNKNOWN_ERROR);
    }
}
