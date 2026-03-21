package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 上下文条目类型枚举
 */
@Getter
@AllArgsConstructor
public enum ContextItemTypeEnum {

    DOCUMENT("document", "文档"),
    CODE("code", "代码"),
    CONFIG("config", "配置"),
    API("api", "API");

    private final String code;
    private final String description;
}
