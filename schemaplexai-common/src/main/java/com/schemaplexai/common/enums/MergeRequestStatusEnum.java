package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 合并请求状态枚举
 */
@Getter
@AllArgsConstructor
public enum MergeRequestStatusEnum {

    OPENED("opened", "已创建"),
    MERGED("merged", "已合并"),
    CLOSED("closed", "已关闭");

    private final String code;
    private final String description;

    public static MergeRequestStatusEnum fromCode(String code) {
        if (code == null) {
            return OPENED;
        }
        for (MergeRequestStatusEnum s : values()) {
            if (s.code.equalsIgnoreCase(code)) {
                return s;
            }
        }
        return OPENED;
    }
}
