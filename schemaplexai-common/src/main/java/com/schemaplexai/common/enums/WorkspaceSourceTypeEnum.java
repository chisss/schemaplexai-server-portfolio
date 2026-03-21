package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作空间来源类型枚举
 */
@Getter
@AllArgsConstructor
public enum WorkspaceSourceTypeEnum {

    GIT("git", "Git仓库导入"),
    LOCAL("local", "本地路径导入"),
    MANUAL("manual", "手动创建");

    private final String code;
    private final String description;
}
