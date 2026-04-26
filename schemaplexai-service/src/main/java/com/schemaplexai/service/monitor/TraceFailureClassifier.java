package com.schemaplexai.service.monitor;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Agent Trace 失败分类器
 */
@Component
public class TraceFailureClassifier {

    public static final String RECOVERABLE_TOOL_EXPLORATION = "RECOVERABLE_TOOL_EXPLORATION";
    public static final String MODEL_CHANNEL = "MODEL_CHANNEL";
    public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";
    public static final String SANDBOX_VIOLATION = "SANDBOX_VIOLATION";
    public static final String BLOCKING_ERROR = "BLOCKING_ERROR";
    public static final String UNKNOWN = "UNKNOWN";

    public TraceFailureClassification classify(String spanType, String name, String status, String reason) {
        if (!"FAILED".equalsIgnoreCase(status) && !"ERROR".equalsIgnoreCase(status)) {
            return null;
        }
        String normalizedReason = normalize(reason + " " + name);
        if (normalizedReason.contains("budget") || normalizedReason.contains("预算") || normalizedReason.contains("cost")) {
            return new TraceFailureClassification(BUDGET_EXCEEDED, reason, false);
        }
        if (normalizedReason.contains("sandbox") || normalizedReason.contains("沙箱") || normalizedReason.contains("越权")) {
            return new TraceFailureClassification(SANDBOX_VIOLATION, reason, false);
        }
        if ("TOOL".equalsIgnoreCase(spanType)) {
            return new TraceFailureClassification(RECOVERABLE_TOOL_EXPLORATION, reason, true);
        }
        if ("LLM".equalsIgnoreCase(spanType) || containsModelChannelSignal(normalizedReason)) {
            return new TraceFailureClassification(MODEL_CHANNEL, reason, true);
        }
        if ("AGENT".equalsIgnoreCase(spanType) || "WORKFLOW".equalsIgnoreCase(spanType)) {
            return new TraceFailureClassification(BLOCKING_ERROR, reason, false);
        }
        return new TraceFailureClassification(UNKNOWN, reason, false);
    }

    public TraceFailureClassification inferExisting(String spanType, String name, String status,
                                                    String failureCategory, String failureReason,
                                                    Boolean recoverable) {
        if (StringUtils.hasText(failureCategory)) {
            return new TraceFailureClassification(failureCategory, failureReason, Boolean.TRUE.equals(recoverable));
        }
        return classify(spanType, name, status, failureReason);
    }

    private boolean containsModelChannelSignal(String text) {
        return text.contains("invalidparameter")
                || text.contains("not support")
                || text.contains("cooldown")
                || text.contains("冷却")
                || text.contains("quota")
                || text.contains("eof")
                || text.contains("模型通道");
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.toLowerCase() : "";
    }
}
