package com.schemaplexai.common.constant;

import com.schemaplexai.common.enums.MessageTemplateTypeEnum;

/**
 * 评审通知相关常量
 */
public final class ReviewNotificationConstant {

    private ReviewNotificationConstant() {}

    // ===== 消息类型 =====
    public static final String MESSAGE_TYPE_REVIEW = "review";

    // ===== 业务类型（对应 MessageTemplateTypeEnum.code）=====
    public static final String BUSINESS_TYPE_COMPLETED = MessageTemplateTypeEnum.REVIEW_COMPLETED.getCode();
    public static final String BUSINESS_TYPE_REMIND = MessageTemplateTypeEnum.REVIEW_REMIND.getCode();
    public static final String BUSINESS_TYPE_ESCALATE = MessageTemplateTypeEnum.REVIEW_ESCALATE.getCode();

    // ===== 来源类型 =====
    public static final String SOURCE_TYPE = "review_session";

    // ===== MQ 事件 =====
    public static final String MQ_EXCHANGE = "sf.workflow.event";
    public static final String EVENT_TYPE_REVIEW_COMPLETED = "review_completed";

    // ===== 管理员角色 =====
    public static final String ADMIN_ROLE_CODE = "admin";

    // ===== 默认消息模板（仅在 sf_message_template 中无对应模板时兜底）=====
    public static final String DEFAULT_COMPLETED_TITLE = "评审已完成";
    public static final String DEFAULT_COMPLETED_CONTENT = "评审会话「${sessionTitle}」已完成，最终决策：${decision}";
    public static final String DEFAULT_REMIND_TITLE = "评审催办提醒";
    public static final String DEFAULT_REMIND_CONTENT = "评审会话「${sessionTitle}」已超过截止时间，请尽快提交评审意见。";
    public static final String DEFAULT_ESCALATE_TITLE = "评审超时升级";
    public static final String DEFAULT_ESCALATE_CONTENT = "评审会话「${sessionTitle}」已超时且未完成，已升级处理。";

    // ===== Action URL 模板 =====
    public static final String ACTION_URL_PATTERN = "/workflow/review/%s";
}
