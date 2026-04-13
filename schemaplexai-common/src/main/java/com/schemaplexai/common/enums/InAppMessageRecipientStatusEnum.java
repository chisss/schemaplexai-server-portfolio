package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 站内信接收状态
 */
@Getter
@AllArgsConstructor
public enum InAppMessageRecipientStatusEnum {

    UNREAD("unread", "未读"),
    READ("read", "已读"),
    ARCHIVED("archived", "已归档");

    private final String code;

    private final String description;
}
