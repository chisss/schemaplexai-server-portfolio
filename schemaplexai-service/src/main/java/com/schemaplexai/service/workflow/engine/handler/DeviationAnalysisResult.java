package com.schemaplexai.service.workflow.engine.handler;

/**
 * 偏离分析单项结果
 */
public record DeviationAnalysisResult(
        String type,
        String severity,
        String title,
        String description,
        String expectedValue,
        String actualValue
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String type;
        private String severity;
        private String title;
        private String description;
        private String expectedValue;
        private String actualValue;

        public Builder type(String type) { this.type = type; return this; }
        public Builder severity(String severity) { this.severity = severity; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder expectedValue(String expectedValue) { this.expectedValue = expectedValue; return this; }
        public Builder actualValue(String actualValue) { this.actualValue = actualValue; return this; }

        public DeviationAnalysisResult build() {
            return new DeviationAnalysisResult(type, severity, title, description, expectedValue, actualValue);
        }
    }
}
