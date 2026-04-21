package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 知识文档状态枚举
 */
@Getter
@AllArgsConstructor
public enum KnowledgeDocumentStatusEnum {

    PENDING("pending", "待处理"),
    PROCESSING("processing", "处理中"),
    SCANNING("scanning", "安全扫描中"),
    COMPLETED("completed", "已完成"),
    FAILED("failed", "失败"),
    BLOCKED("blocked", "已拦截");

    private final String code;
    private final String description;
}
