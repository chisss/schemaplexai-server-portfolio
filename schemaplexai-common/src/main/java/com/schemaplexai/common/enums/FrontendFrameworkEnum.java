package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 前端框架枚举
 */
@Getter
@AllArgsConstructor
public enum FrontendFrameworkEnum {

    REACT("react", "React"),
    VUE("vue", "Vue"),
    VANILLA("vanilla", "原生 JavaScript");

    private final String code;
    private final String description;

    public static FrontendFrameworkEnum fromCode(String code) {
        if (code == null) {
            return REACT;
        }
        for (FrontendFrameworkEnum framework : values()) {
            if (framework.code.equalsIgnoreCase(code)) {
                return framework;
            }
        }
        return REACT;
    }

    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (FrontendFrameworkEnum framework : values()) {
            if (framework.code.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }
}
