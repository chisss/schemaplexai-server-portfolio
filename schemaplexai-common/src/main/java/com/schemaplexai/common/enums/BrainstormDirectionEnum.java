package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 思维导图方向枚举
 */
@Getter
@AllArgsConstructor
public enum BrainstormDirectionEnum {

    LR("LR", "从左到右"),
    RL("RL", "从右到左"),
    TB("TB", "从上到下"),
    BT("BT", "从下到上");

    private final String code;
    private final String description;

    public static BrainstormDirectionEnum fromCode(String code) {
        if (code == null) {
            return LR;
        }
        for (BrainstormDirectionEnum direction : values()) {
            if (direction.code.equalsIgnoreCase(code)) {
                return direction;
            }
        }
        return LR;
    }

    public static boolean isValid(String code) {
        if (code == null) {
            return false;
        }
        for (BrainstormDirectionEnum direction : values()) {
            if (direction.code.equalsIgnoreCase(code)) {
                return true;
            }
        }
        return false;
    }
}
