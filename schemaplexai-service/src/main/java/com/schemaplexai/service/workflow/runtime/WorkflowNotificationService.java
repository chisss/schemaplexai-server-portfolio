package com.schemaplexai.service.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.MessageTemplateTypeEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.MessageTemplateMapper;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.entity.MessageTemplate;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.service.integration.notification.NotificationDispatchService;
import com.schemaplexai.service.integration.notification.model.NotificationDispatchRequest;
import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流通知服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowNotificationService {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotificationChannelMapper notificationChannelMapper;
    private final MessageTemplateMapper messageTemplateMapper;
    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final SpecMapper specMapper;
    private final NotificationDispatchService notificationDispatchService;

    @Value("${schemaplexai.frontend-base-url:}")
    private String frontendBaseUrl;

    public Map<String, Object> sendWorkflowCompletedNotification(WorkflowInstance instance,
                                                                 WorkflowNodeExecution nodeExecution,
                                                                 Map<String, Object> nodeConfig) {
        NotificationChannel channel = resolveNotificationNodeChannel(nodeConfig);
        ResolvedNotificationMessage resolvedMessage = resolveNotificationNodeMessage(instance, nodeExecution, nodeConfig, channel);
        NotificationSendResult result = notificationDispatchService.send(NotificationDispatchRequest.builder()
                .channel(channel)
                .message(resolvedMessage.message())
                .templateId(resolvedMessage.templateId())
                .templateName(resolvedMessage.templateName())
                .templateType(resolvedMessage.templateType())
                .sourceType("workflow_notification")
                .sourceId(instance.getId())
                .businessType(StringUtils.hasText(resolvedMessage.templateType())
                        ? resolvedMessage.templateType() : "workflow_notification")
                .workflowInstanceId(instance.getId())
                .workflowNodeExecutionId(nodeExecution != null ? nodeExecution.getId() : null)
                .specId(instance.getSpecId())
                .build());

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("channelId", channel.getId());
        output.put("channelName", channel.getName());
        output.put("channelType", channel.getChannelType());
        output.put("messageResponse", result.getResponseSummary());
        output.put("recordId", result.getRecordId());
        output.put("previewUrl", resolvedMessage.message().getPreviewUrl());
        output.put("billingAmount", result.getBillingAmount());
        output.put("billingCurrency", result.getBillingCurrency());
        output.put("sentAt", formatDateTime(LocalDateTime.now()));
        return output;
    }

    public List<Map<String, Object>> sendHumanReviewNotifications(WorkflowInstance instance,
                                                                  WorkflowNodeExecution nodeExecution,
                                                                  Map<String, Object> nodeConfig) {
        return sendHumanReviewNotifications(instance, nodeExecution, nodeConfig,
                buildSpecReviewActionUrl(instance.getSpecId(), instance.getId(), nodeExecution.getNodeId(), null));
    }

    public List<Map<String, Object>> sendHumanReviewNotifications(WorkflowInstance instance,
                                                                  WorkflowNodeExecution nodeExecution,
                                                                  Map<String, Object> nodeConfig,
                                                                  String reviewUrl) {
        List<NotificationChannel> channels = resolveHumanReviewChannels(nodeConfig);
        if (channels.isEmpty()) {
            return List.of();
        }

        ResolvedNotificationMessage resolvedMessage = resolveHumanReviewMessage(instance, nodeExecution, nodeConfig, reviewUrl);
        NotificationMessage message = resolvedMessage.message();

        List<Map<String, Object>> results = new ArrayList<>();
        for (NotificationChannel channel : channels) {
            try {
                validateTemplateChannelCompatibility(resolveTemplateById(resolvedMessage.templateId()), channel);
                NotificationSendResult result = notificationDispatchService.send(NotificationDispatchRequest.builder()
                        .channel(channel)
                        .message(message)
                        .templateId(resolvedMessage.templateId())
                        .templateName(resolvedMessage.templateName())
                        .templateType(resolvedMessage.templateType())
                        .sourceType("human_review")
                        .sourceId(nodeExecution != null ? nodeExecution.getId() : instance.getId())
                        .businessType(MessageTemplateTypeEnum.HUMAN_REVIEW_PENDING.getCode())
                        .workflowInstanceId(instance.getId())
                        .workflowNodeExecutionId(nodeExecution != null ? nodeExecution.getId() : null)
                        .specId(instance.getSpecId())
                        .build());
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("channelId", channel.getId());
                item.put("channelName", channel.getName());
                item.put("channelType", channel.getChannelType());
                item.put("messageResponse", result.getResponseSummary());
                item.put("recordId", result.getRecordId());
                item.put("billingAmount", result.getBillingAmount());
                item.put("billingCurrency", result.getBillingCurrency());
                results.add(item);
            } catch (Exception ex) {
                log.error("人工审核节点外部通知失败: instanceId={}, nodeId={}, channelId={}",
                        instance.getId(), nodeExecution.getNodeId(), channel.getId(), ex);
            }
        }
        return results;
    }

    public ResolvedNotificationMessage resolveHumanReviewMessage(WorkflowInstance instance,
                                                                 WorkflowNodeExecution nodeExecution,
                                                                 Map<String, Object> nodeConfig,
                                                                 String reviewUrl) {
        String templateId = str(nodeConfig, "messageTemplateId");
        MessageTemplate template = resolveTemplateById(templateId);
        if (template == null) {
            template = resolveActiveTemplate(MessageTemplateTypeEnum.HUMAN_REVIEW_PENDING.getCode());
        }
        Map<String, Object> variables = buildWorkflowVariables(instance, nodeExecution,
                MessageTemplateTypeEnum.HUMAN_REVIEW_PENDING.getCode(), reviewUrl);
        variables.put("reviewerRoles", joinRoles(nodeConfig.get("reviewerRoles")));
        if (template != null) {
            NotificationMessage message = NotificationMessage.builder()
                    .title(renderTemplate(template.getTitleTemplate(), variables))
                    .content(renderTemplate(template.getContentTemplate(), variables))
                    .previewUrl(reviewUrl)
                    .build();
            return new ResolvedNotificationMessage(message, template.getId(), template.getName(), template.getTemplateType());
        }
        NotificationMessage message = NotificationMessage.builder()
                .title("工作流《" + variables.get("workflowTemplateName") + "》待人工审核")
                .content("""
工作流实例：%s
审核节点：%s
审核角色：%s
关联 Spec：%s
请进入平台完成审核：%s
""".formatted(
                        variables.get("workflowInstanceName"),
                        variables.get("currentNodeLabel"),
                        variables.get("reviewerRoles"),
                        variables.get("specTitle"),
                        reviewUrl))
                .previewUrl(reviewUrl)
                .build();
        return new ResolvedNotificationMessage(message, null, null, MessageTemplateTypeEnum.HUMAN_REVIEW_PENDING.getCode());
    }

    public String buildSpecReviewActionUrl(String specId, String instanceId, String nodeId, String sessionId) {
        return buildAbsoluteUrl(buildSpecReviewActionPath(specId, instanceId, nodeId, sessionId));
    }

    public String buildSpecReviewActionPath(String specId, String instanceId, String nodeId, String sessionId) {
        StringBuilder builder = new StringBuilder("/approval-center?type=workflow_review");
        if (StringUtils.hasText(sessionId)) {
            builder.append("&id=").append(sessionId);
        }
        if (StringUtils.hasText(specId)) {
            builder.append("&specId=").append(specId);
        }
        if (StringUtils.hasText(instanceId)) {
            builder.append("&instanceId=").append(instanceId);
        }
        if (StringUtils.hasText(nodeId)) {
            builder.append("&nodeId=").append(nodeId);
        }
        return builder.toString();
    }

    private NotificationChannel resolveNotificationNodeChannel(Map<String, Object> nodeConfig) {
        String channelId = str(nodeConfig, "channelId");
        if (StringUtils.hasText(channelId)) {
            NotificationChannel channel = notificationChannelMapper.selectById(channelId);
            if (channel == null) {
                throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);
            }
            return channel;
        }

        String channelType = str(nodeConfig, "channel");
        if (!StringUtils.hasText(channelType)) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND, "通信渠道节点未绑定有效消息渠道");
        }
        NotificationChannel channel = notificationChannelMapper.selectOne(
                new LambdaQueryWrapper<NotificationChannel>()
                        .eq(NotificationChannel::getChannelType, channelType)
                        .eq(NotificationChannel::getStatus, CommonConstant.STATUS_ACTIVE)
                        .orderByDesc(NotificationChannel::getUpdatedAt)
                        .last("limit 1")
        );
        if (channel == null) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND,
                    "未找到已启用的渠道类型: " + channelType);
        }
        return channel;
    }

    private ResolvedNotificationMessage resolveNotificationNodeMessage(WorkflowInstance instance,
                                                                      WorkflowNodeExecution nodeExecution,
                                                                      Map<String, Object> nodeConfig,
                                                                      NotificationChannel channel) {
        String messageTemplateId = str(nodeConfig, "messageTemplateId");
        if (StringUtils.hasText(messageTemplateId)) {
            MessageTemplate template = messageTemplateMapper.selectById(messageTemplateId);
            if (template == null) {
                throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_NOT_FOUND);
            }
            validateTemplateChannelCompatibility(template, channel);
            Map<String, Object> variables = buildWorkflowVariables(instance, nodeExecution, template.getTemplateType());
            NotificationMessage message = NotificationMessage.builder()
                    .title(renderTemplate(template.getTitleTemplate(), variables))
                    .content(renderTemplate(template.getContentTemplate(), variables))
                    .previewUrl((String) variables.get("previewUrl"))
                    .build();
            return new ResolvedNotificationMessage(
                    message,
                    template.getId(),
                    template.getName(),
                    template.getTemplateType()
            );
        }

        String title = str(nodeConfig, "title");
        String content = str(nodeConfig, "messageTemplate");
        if (!StringUtils.hasText(title) && !StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_NOT_FOUND, "通信渠道节点未绑定消息模板");
        }
        Map<String, Object> variables = buildWorkflowVariables(instance, nodeExecution, null);
        NotificationMessage message = NotificationMessage.builder()
                .title(renderTemplate(StringUtils.hasText(title) ? title : "工作流通知", variables))
                .content(renderTemplate(content, variables))
                .previewUrl((String) variables.get("previewUrl"))
                .build();
        return new ResolvedNotificationMessage(message, null, null, null);
    }

    private List<NotificationChannel> resolveHumanReviewChannels(Map<String, Object> nodeConfig) {
        List<NotificationChannel> channels = new ArrayList<>();
        List<String> ids = stringList(nodeConfig.get("notificationChannelIds"));
        for (String id : ids) {
            NotificationChannel channel = notificationChannelMapper.selectById(id);
            if (channel != null && CommonConstant.STATUS_ACTIVE.equals(channel.getStatus())) {
                channels.add(channel);
            }
        }
        if (!channels.isEmpty()) {
            return channels;
        }

        List<String> channelTypes = stringList(nodeConfig.get("notificationChannels"));
        for (String channelType : channelTypes) {
            NotificationChannel channel = notificationChannelMapper.selectOne(
                    new LambdaQueryWrapper<NotificationChannel>()
                            .eq(NotificationChannel::getChannelType, channelType)
                            .eq(NotificationChannel::getStatus, CommonConstant.STATUS_ACTIVE)
                            .orderByDesc(NotificationChannel::getUpdatedAt)
                            .last("limit 1")
            );
            if (channel != null) {
                channels.add(channel);
            }
        }
        return channels;
    }

    private Map<String, Object> buildWorkflowVariables(WorkflowInstance instance,
                                                       WorkflowNodeExecution nodeExecution,
                                                       String templateType) {
        Spec spec = StringUtils.hasText(instance.getSpecId()) ? specMapper.selectById(instance.getSpecId()) : null;
        String externalDeliveryUrl = resolveArtifactDeliveryPreviewUrl(instance);
        String previewUrl = StringUtils.hasText(externalDeliveryUrl)
                ? externalDeliveryUrl
                : spec != null && StringUtils.hasText(spec.getPrimaryArtifactId())
                ? buildArtifactPreviewUrl(spec.getPrimaryArtifactId())
                : buildWorkflowInstancePreviewUrl(instance.getId());
        return buildWorkflowVariables(instance, nodeExecution, templateType, previewUrl);
    }

    private Map<String, Object> buildWorkflowVariables(WorkflowInstance instance,
                                                       WorkflowNodeExecution nodeExecution,
                                                       String templateType,
                                                       String previewUrl) {
        WorkflowTemplate workflowTemplate = workflowTemplateMapper.selectById(instance.getTemplateId());
        Spec spec = StringUtils.hasText(instance.getSpecId()) ? specMapper.selectById(instance.getSpecId()) : null;

        Map<String, Object> variables = new HashMap<>();
        variables.put("workflowTemplateName", workflowTemplate != null ? workflowTemplate.getName() : "-");
        variables.put("workflowInstanceName", safeValue(instance.getName()));
        variables.put("workflowStatus", resolveWorkflowStatus(instance, templateType));
        variables.put("specTitle", spec != null && StringUtils.hasText(spec.getName()) ? spec.getName() : "-");
        variables.put("specId", spec != null && StringUtils.hasText(spec.getId()) ? spec.getId() : "-");
        variables.put("currentNodeLabel", nodeExecution != null && StringUtils.hasText(nodeExecution.getNodeLabel())
                ? nodeExecution.getNodeLabel() : "-");
        variables.put("startedAt", formatDateTime(instance.getStartedAt()));
        variables.put("completedAt", formatDateTime(LocalDateTime.now()));
        variables.put("previewUrl", previewUrl);
        variables.put("specPreviewUrl", spec != null ? buildSpecPreviewUrl(spec.getId()) : "-");
        variables.put("artifactDeliveryUrl", safeValue(readArtifactDeliveryVariable(instance, "artifactDeliveryUrl")));
        variables.put("artifactDeliveryType", safeValue(readArtifactDeliveryVariable(instance, "artifactDeliveryType")));
        variables.put("artifactDeliveryDocumentId", safeValue(readArtifactDeliveryVariable(instance, "artifactDeliveryDocumentId")));
        return variables;
    }

    private String resolveWorkflowStatus(WorkflowInstance instance, String templateType) {
        if (instance == null) {
            return "-";
        }
        if (MessageTemplateTypeEnum.WORKFLOW_COMPLETED.getCode().equals(templateType)) {
            return WorkflowInstanceStatusEnum.COMPLETED.getCode();
        }
        if (MessageTemplateTypeEnum.WORKFLOW_FAILED.getCode().equals(templateType)) {
            return WorkflowInstanceStatusEnum.FAILED.getCode();
        }
        return safeValue(instance.getStatus());
    }

    private String renderTemplate(String template, Map<String, Object> variables) {
        if (!StringUtils.hasText(template)) {
            return "";
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = safeValue(variables.get(key));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    @SuppressWarnings("unchecked")
    private String resolveArtifactDeliveryPreviewUrl(WorkflowInstance instance) {
        String previewUrl = readArtifactDeliveryVariable(instance, "artifactDeliveryUrl");
        if (StringUtils.hasText(previewUrl)) {
            return previewUrl;
        }
        if (instance == null || instance.getVariables() == null) {
            return null;
        }
        for (Object value : instance.getVariables().values()) {
            if (value instanceof Map<?, ?> map) {
                Object nestedUrl = ((Map<String, Object>) map).get("deliveryUrl");
                if (nestedUrl != null && StringUtils.hasText(String.valueOf(nestedUrl))) {
                    return String.valueOf(nestedUrl);
                }
            }
        }
        return null;
    }

    private String readArtifactDeliveryVariable(WorkflowInstance instance, String key) {
        if (instance == null || instance.getVariables() == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object value = instance.getVariables().get(key);
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            return String.valueOf(value);
        }
        return findNestedArtifactValue(instance.getVariables(), key);
    }

    @SuppressWarnings("unchecked")
    private String findNestedArtifactValue(Map<String, Object> variables, String key) {
        if (variables == null || variables.isEmpty()) {
            return null;
        }
        Set<String> aliases = switch (key) {
            case "artifactDeliveryUrl" -> Set.of("artifactDeliveryUrl", "deliveryUrl", "previewUrl");
            case "artifactDeliveryDocumentId" -> Set.of("artifactDeliveryDocumentId", "deliveryDocumentId", "documentId");
            case "artifactDeliveryType" -> Set.of("artifactDeliveryType", "deliveryType");
            default -> Set.of(key);
        };
        for (Object value : variables.values()) {
            String resolved = findNestedArtifactValue(value, aliases);
            if (StringUtils.hasText(resolved)) {
                return resolved;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String findNestedArtifactValue(Object value, Set<String> aliases) {
        if (value instanceof Map<?, ?> mapValue) {
            for (String alias : aliases) {
                Object direct = ((Map<String, Object>) mapValue).get(alias);
                if (direct != null && StringUtils.hasText(String.valueOf(direct))) {
                    return String.valueOf(direct);
                }
            }
            for (Object nestedValue : ((Map<String, Object>) mapValue).values()) {
                String resolved = findNestedArtifactValue(nestedValue, aliases);
                if (StringUtils.hasText(resolved)) {
                    return resolved;
                }
            }
            return null;
        }
        if (value instanceof Iterable<?> iterableValue) {
            for (Object nestedValue : iterableValue) {
                String resolved = findNestedArtifactValue(nestedValue, aliases);
                if (StringUtils.hasText(resolved)) {
                    return resolved;
                }
            }
        }
        return null;
    }

    private String buildWorkflowInstancePreviewUrl(String instanceId) {
        return buildAbsoluteUrl("/workflow/" + instanceId);
    }

    private String buildSpecPreviewUrl(String specId) {
        return buildAbsoluteUrl("/spec/" + specId);
    }

    private String buildArtifactPreviewUrl(String artifactId) {
        return buildAbsoluteUrl("/artifacts/" + artifactId);
    }

    private String buildAbsoluteUrl(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        String baseUrl = trimTrailingSlash(frontendBaseUrl);
        if (!StringUtils.hasText(baseUrl)) {
            return normalizedPath;
        }
        return baseUrl + normalizedPath;
    }

    private String trimTrailingSlash(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String safeValue(Object value) {
        return value == null ? "-" : String.valueOf(value);
    }

    private String formatDateTime(LocalDateTime value) {
        return value == null ? "-" : value.format(DATE_TIME_FORMATTER);
    }

    private String str(Map<String, Object> source, String key) {
        if (source == null || !source.containsKey(key) || source.get(key) == null) {
            return null;
        }
        return String.valueOf(source.get(key)).trim();
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object source) {
        if (!(source instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(item -> item != null && StringUtils.hasText(String.valueOf(item)))
                .map(item -> String.valueOf(item).trim())
                .toList();
    }

    private String joinRoles(Object roles) {
        List<String> roleList = stringList(roles);
        return roleList.isEmpty() ? "-" : String.join("、", roleList);
    }

    private void validateTemplateChannelCompatibility(MessageTemplate template, NotificationChannel channel) {
        if (template == null || channel == null || template.getSupportedChannels() == null || template.getSupportedChannels().isEmpty()) {
            return;
        }
        if (!template.getSupportedChannels().contains(channel.getChannelType())) {
            throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_CHANNEL_INVALID,
                    "模板《" + template.getName() + "》不支持渠道类型: " + channel.getChannelType());
        }
    }

    private MessageTemplate resolveTemplateById(String templateId) {
        if (!StringUtils.hasText(templateId)) {
            return null;
        }
        return messageTemplateMapper.selectById(templateId);
    }

    private MessageTemplate resolveActiveTemplate(String templateType) {
        return messageTemplateMapper.selectOne(
                new LambdaQueryWrapper<MessageTemplate>()
                        .eq(MessageTemplate::getTemplateType, templateType)
                        .eq(MessageTemplate::getStatus, CommonConstant.STATUS_ACTIVE)
                        .orderByDesc(MessageTemplate::getUpdatedAt)
                        .last("LIMIT 1")
        );
    }

    public record ResolvedNotificationMessage(NotificationMessage message,
                                              String templateId,
                                              String templateName,
                                              String templateType) {
    }
}
