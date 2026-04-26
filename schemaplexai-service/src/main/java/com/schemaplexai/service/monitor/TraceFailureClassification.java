package com.schemaplexai.service.monitor;

/**
 * Trace 失败分类结果
 */
public record TraceFailureClassification(String category,
                                         String reason,
                                         boolean recoverable) {
}
