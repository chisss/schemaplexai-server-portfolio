package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 自定义工具类型枚举
 */
@Getter
@AllArgsConstructor
public enum CustomToolTypeEnum {

    JAVA("java", "Java 类", "实现 CustomToolExecutor 接口的 Java 类"),
    HTTP("http", "HTTP API", "外部 REST API 调用"),
    JAVASCRIPT("javascript", "JavaScript", "Nashorn 引擎执行 JS 脚本"),
    PYTHON("python", "Python", "Process 执行 Python 脚本");

    private final String code;
    private final String description;
    private final String remark;

    public static CustomToolTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (CustomToolTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }

    public boolean isScript() {
        return this == JAVASCRIPT || this == PYTHON;
    }

    public boolean isHttp() {
        return this == HTTP;
    }

    public boolean isJava() {
        return this == JAVA;
    }
}
