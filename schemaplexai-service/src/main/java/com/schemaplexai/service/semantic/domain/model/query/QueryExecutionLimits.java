package com.schemaplexai.service.semantic.domain.model.query;

/** 查询执行资源限制。 */
public record QueryExecutionLimits(int maxRows, int timeoutSeconds) {

    public QueryExecutionLimits {
        if (maxRows < 1 || maxRows > 5000) {
            throw new IllegalArgumentException("maxRows must be between 1 and 5000");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
        }
    }

    public static QueryExecutionLimits defaults() {
        return new QueryExecutionLimits(5000, 30);
    }
}
