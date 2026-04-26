package com.schemaplexai.service.agent.tool.audit;

/**
 * 工具执行失败分类结果
 */
public record ToolExecutionClassification(String errorCode,
                                          String failureCategory,
                                          boolean recoverable) {
}
