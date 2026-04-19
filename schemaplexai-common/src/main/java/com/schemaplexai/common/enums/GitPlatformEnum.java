package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Git 平台枚举
 */
@Getter
@AllArgsConstructor
public enum GitPlatformEnum {

    GITHUB("github", "GitHub", "https://api.github.com"),
    GITLAB("gitlab", "GitLab", ""),
    GITEE("gitee", "Gitee", "https://gitee.com/api/v5"),
    BITBUCKET("bitbucket", "Bitbucket", "https://api.bitbucket.org/2.0");

    private final String code;
    private final String displayName;
    /** 默认 API URL（GitLab 需用户自行配置） */
    private final String defaultApiUrl;

    public static GitPlatformEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (GitPlatformEnum p : values()) {
            if (p.code.equalsIgnoreCase(code)) {
                return p;
            }
        }
        return null;
    }
}
