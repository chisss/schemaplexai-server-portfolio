package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * CSS 样式枚举
 */
@Getter
@AllArgsConstructor
public enum CssStyleEnum {

    TAILWIND("tailwind", "Tailwind CSS"),
    STYLED_COMPONENTS("styled-components", "Styled Components"),
    CSS_MODULES("css-modules", "CSS Modules");

    private final String code;
    private final String description;

    public static CssStyleEnum fromCode(String code) {
        if (code == null) {
            return TAILWIND;
        }
        for (CssStyleEnum style : values()) {
            if (style.code.equalsIgnoreCase(code)) {
                return style;
            }
        }
        return TAILWIND;
    }

    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (CssStyleEnum style : values()) {
            if (style.code.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }
}
