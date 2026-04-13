package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知渠道类型枚举
 */
@Getter
@AllArgsConstructor
public enum NotificationChannelTypeEnum {

    IN_APP("in_app", "站内信"),
    DINGTALK("dingtalk", "钉钉"),
    WECHAT_WORK("wechat_work", "企业微信"),
    FEISHU("feishu", "飞书"),
    SLACK("slack", "Slack"),
    EMAIL("email", "邮件"),
    SMS("sms", "短信");

    private final String code;
    private final String description;

    public static boolean isValid(String code) {
        for (NotificationChannelTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
