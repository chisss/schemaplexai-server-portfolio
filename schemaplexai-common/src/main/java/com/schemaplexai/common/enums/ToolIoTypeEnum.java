package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工具 IO 类型枚举
 */
@Getter
@AllArgsConstructor
public enum ToolIoTypeEnum {

    READ("READ", "只读工具"),
    WRITE("WRITE", "写入工具"),
    READ_WRITE("READ_WRITE", "读写工具");

    private final String code;
    private final String description;

    public static ToolIoTypeEnum fromCode(String code) {
        if (code == null) return READ_WRITE;
        for (ToolIoTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return READ_WRITE;
    }

    public boolean isWrite() {
        return this == WRITE || this == READ_WRITE;
    }
}
