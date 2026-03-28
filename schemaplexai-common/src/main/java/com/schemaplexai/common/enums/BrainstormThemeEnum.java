package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 思维导图主题枚举
 */
@Getter
@AllArgsConstructor
public enum BrainstormThemeEnum {

    DEFAULT("default", "默认主题"),
    DARK("dark", "暗色主题"),
    COLORFUL("colorful", "彩色主题");

    private final String code;
    private final String description;

    public static BrainstormThemeEnum fromCode(String code) {
        if (code == null) {
            return DEFAULT;
        }
        for (BrainstormThemeEnum theme : values()) {
            if (theme.code.equalsIgnoreCase(code)) {
                return theme;
            }
        }
        return DEFAULT;
    }

    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (BrainstormThemeEnum theme : values()) {
            if (theme.code.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }
}
