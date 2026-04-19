package com.schemaplexai.service.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.constant.ReviewNotificationConstant;
import com.schemaplexai.dao.mapper.MessageTemplateMapper;
import com.schemaplexai.model.entity.MessageTemplate;
import com.schemaplexai.service.notification.InAppMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 评审通知辅助服务
 * <p>
 * 优先查找 sf_message_template 中对应 templateType 的活跃模板进行渲染，
 * 找不到时使用 {@link ReviewNotificationConstant} 中的默认消息兜底。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewNotificationHelper {

    private final InAppMessageService inAppMessageService;
    private final MessageTemplateMapper messageTemplateMapper;

    /**
     * 发送评审相关站内信
     *
     * @param tenantId       租户 ID
     * @param templateType   模板类型（对应 MessageTemplateTypeEnum.code）
     * @param defaultTitle   默认标题（模板不存在时兜底）
     * @param defaultContent 默认内容（模板不存在时兜底）
     * @param variables      模板变量
     * @param sourceId       来源 ID（评审会话 ID）
     * @param recipientUserIds 接收人用户 ID 列表
     */
    public void sendReviewNotification(String tenantId,
                                       String templateType,
                                       String defaultTitle,
                                       String defaultContent,
                                       Map<String, String> variables,
                                       String sourceId,
                                       List<String> recipientUserIds) {
        if (recipientUserIds == null || recipientUserIds.isEmpty()) {
            return;
        }

        String title = defaultTitle;
        String content = defaultContent;

        // 优先使用消息模板
        MessageTemplate template = resolveActiveTemplate(templateType);
        if (template != null) {
            title = renderTemplate(template.getTitleTemplate(), variables);
            content = renderTemplate(template.getContentTemplate(), variables);
        } else {
            // 兜底：用默认消息 + 变量替换
            title = renderTemplate(title, variables);
            content = renderTemplate(content, variables);
        }

        String actionUrl = String.format(ReviewNotificationConstant.ACTION_URL_PATTERN, sourceId);

        try {
            inAppMessageService.createMessage(
                    tenantId, title, content,
                    ReviewNotificationConstant.MESSAGE_TYPE_REVIEW,
                    templateType,
                    ReviewNotificationConstant.SOURCE_TYPE,
                    sourceId, actionUrl,
                    Map.of("sessionId", sourceId),
                    recipientUserIds);
        } catch (Exception e) {
            // 通知失败不阻塞主流程
            log.error("评审通知发送失败: templateType={}, sessionId={}, error={}",
                    templateType, sourceId, e.getMessage());
        }
    }

    /**
     * 查找指定类型的活跃消息模板
     */
    private MessageTemplate resolveActiveTemplate(String templateType) {
        return messageTemplateMapper.selectOne(
                new LambdaQueryWrapper<MessageTemplate>()
                        .eq(MessageTemplate::getTemplateType, templateType)
                        .eq(MessageTemplate::getStatus, CommonConstant.STATUS_ACTIVE)
                        .orderByDesc(MessageTemplate::getUpdatedAt)
                        .last("LIMIT 1"));
    }

    /**
     * 渲染模板变量（${key} -> value）
     */
    private String renderTemplate(String template, Map<String, String> variables) {
        if (template == null || variables == null) {
            return template;
        }
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }
}
