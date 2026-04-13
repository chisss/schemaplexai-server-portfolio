package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 消息模板类型枚举
 */
@Getter
@AllArgsConstructor
public enum MessageTemplateTypeEnum {

    WORKFLOW_COMPLETED("workflow_completed", "工作流完成通知"),
    WORKFLOW_FAILED("workflow_failed", "工作流失败告警"),
    HUMAN_REVIEW_PENDING("human_review_pending", "人工审核待办通知"),
    APPROVAL_RESULT("approval_result", "审批结果通知"),
    SYSTEM_ALERT("system_alert", "系统告警通知"),
    PROJECT_SYNC_SUMMARY("project_sync_summary", "项目同步摘要");

    private final String code;

    private final String description;

    public static MessageTemplateTypeEnum fromCode(String code) {
        for (MessageTemplateTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知的消息模板类型: " + code);
    }

    public static boolean isValid(String code) {
        for (MessageTemplateTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return true;
            }
        }
        return false;
    }
}
