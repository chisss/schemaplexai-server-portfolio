package com.schemaplexai.common.enums;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Team Agent 成员角色类型
 */
@Getter
@AllArgsConstructor
public enum TeamMemberRoleTypeEnum {

    LEAD_AGENT("lead_agent", "团队负责人"),
    MEMBER("member", "通用成员");

    private final String code;
    private final String description;

    public boolean matches(String code) {
        return StrUtil.isNotBlank(code) && this.code.equalsIgnoreCase(code);
    }

    public static TeamMemberRoleTypeEnum fromCode(String code) {
        if (StrUtil.isBlank(code)) {
            return MEMBER;
        }
        for (TeamMemberRoleTypeEnum value : values()) {
            if (value.code.equalsIgnoreCase(code)) {
                return value;
            }
        }
        return MEMBER;
    }
}
