package com.schemaplexai.common.enums;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 沙箱策略级别
 */
@Getter
@AllArgsConstructor
public enum SandboxProfileEnum {

    STRICT("strict", "严格"),
    STANDARD("standard", "标准"),
    PERMISSIVE("permissive", "宽松");

    private final String code;
    private final String description;

    public static SandboxProfileEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return STANDARD;
        }
        for (SandboxProfileEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return STANDARD;
    }
}
