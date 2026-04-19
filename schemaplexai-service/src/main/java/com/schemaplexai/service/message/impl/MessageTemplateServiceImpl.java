package com.schemaplexai.service.message.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.MessageTemplateTypeEnum;
import com.schemaplexai.common.enums.NotificationChannelTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.MessageTemplateMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.model.converter.MessageTemplateConverter;
import com.schemaplexai.model.dto.message.MessageTemplateCreateRequest;
import com.schemaplexai.model.dto.message.MessageTemplateQueryRequest;
import com.schemaplexai.model.dto.message.MessageTemplateUpdateRequest;
import com.schemaplexai.model.entity.MessageTemplate;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.vo.message.MessageTemplateMetadataVO;
import com.schemaplexai.model.vo.message.MessageTemplateVO;
import com.schemaplexai.model.vo.message.MessageTemplateVariableVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.message.MessageTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 消息模板服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageTemplateServiceImpl implements MessageTemplateService {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)}");

    private final MessageTemplateMapper messageTemplateMapper;
    private final MessageTemplateConverter messageTemplateConverter;
    private final EntityValidator entityValidator;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageTemplateVO create(MessageTemplateCreateRequest request) {
        ensureNameUnique(null, request.getName());
        validateTemplate(request.getTemplateType(), request.getTitleTemplate(), request.getContentTemplate());
        validateSupportedChannels(request.getSupportedChannels());

        MessageTemplate entity = messageTemplateConverter.fromCreateRequest(request);
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        messageTemplateMapper.insert(entity);
        log.info("创建消息模板成功: templateId={}, name={}, type={}",
                entity.getId(), entity.getName(), entity.getTemplateType());
        return enrichTemplateVO(messageTemplateConverter.toVO(entity), entity);
    }

    @Override
    public PageResult<MessageTemplateVO> page(MessageTemplateQueryRequest request) {
        Page<MessageTemplate> page = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<MessageTemplate> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(request.getTemplateType())) {
            wrapper.eq(MessageTemplate::getTemplateType, request.getTemplateType());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(MessageTemplate::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getName())) {
            wrapper.like(MessageTemplate::getName, request.getName());
        }
        if (StringUtils.hasText(request.getCreatedByKeyword())) {
            Set<String> creatorIds = resolveCreatorIds(request.getCreatedByKeyword());
            if (creatorIds.isEmpty()) {
                return new PageResult<>(List.of(), 0, request.getPage(), request.getSize());
            }
            wrapper.in(MessageTemplate::getCreatedBy, creatorIds);
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(MessageTemplate::getName, request.getKeyword())
                    .or().like(MessageTemplate::getDescription, request.getKeyword()));
        }
        wrapper.orderByDesc(MessageTemplate::getCreatedAt);

        Page<MessageTemplate> result = messageTemplateMapper.selectPage(page, wrapper);
        List<MessageTemplateVO> records = enrichTemplateVOs(
                messageTemplateConverter.toVOList(result.getRecords()),
                result.getRecords()
        );
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public MessageTemplateVO getById(String id) {
        MessageTemplate entity = requireExists(id);
        return enrichTemplateVO(messageTemplateConverter.toVO(entity), entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageTemplateVO update(String id, MessageTemplateUpdateRequest request) {
        MessageTemplate entity = requireExists(id);

        String targetName = StringUtils.hasText(request.getName()) ? request.getName() : entity.getName();
        String targetType = StringUtils.hasText(request.getTemplateType()) ? request.getTemplateType() : entity.getTemplateType();
        String targetTitle = StringUtils.hasText(request.getTitleTemplate()) ? request.getTitleTemplate() : entity.getTitleTemplate();
        String targetContent = StringUtils.hasText(request.getContentTemplate()) ? request.getContentTemplate() : entity.getContentTemplate();

        ensureNameUnique(id, targetName);
        validateTemplate(targetType, targetTitle, targetContent);
        validateSupportedChannels(request.getSupportedChannels());

        if (StringUtils.hasText(request.getName())) {
            entity.setName(request.getName());
        }
        if (StringUtils.hasText(request.getTemplateType())) {
            entity.setTemplateType(request.getTemplateType());
        }
        if (StringUtils.hasText(request.getTitleTemplate())) {
            entity.setTitleTemplate(request.getTitleTemplate());
        }
        if (StringUtils.hasText(request.getContentTemplate())) {
            entity.setContentTemplate(request.getContentTemplate());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
        }
        if (request.getSupportedChannels() != null) {
            entity.setSupportedChannels(request.getSupportedChannels());
        }
        if (StringUtils.hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }

        messageTemplateMapper.updateById(entity);
        log.info("更新消息模板成功: templateId={}", id);
        return enrichTemplateVO(messageTemplateConverter.toVO(entity), entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireExists(id);
        messageTemplateMapper.deleteById(id);
        log.info("删除消息模板成功: templateId={}", id);
    }

    @Override
    public List<MessageTemplateMetadataVO> listDefinitions() {
        return new ArrayList<>(definitionMap().values());
    }

    private MessageTemplate requireExists(String id) {
        return entityValidator.requireExists(messageTemplateMapper, id, ResultCode.MESSAGE_TEMPLATE_NOT_FOUND);
    }

    private void ensureNameUnique(String currentId, String name) {
        LambdaQueryWrapper<MessageTemplate> wrapper = new LambdaQueryWrapper<MessageTemplate>()
                .eq(MessageTemplate::getName, name);
        if (StringUtils.hasText(currentId)) {
            wrapper.ne(MessageTemplate::getId, currentId);
        }
        Long count = messageTemplateMapper.selectCount(wrapper);
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_NAME_DUPLICATE);
        }
    }

    private void validateTemplate(String templateType, String titleTemplate, String contentTemplate) {
        MessageTemplateMetadataVO metadata = getRequiredDefinition(templateType);
        Set<String> supportedKeys = metadata.getSupportedVariables().stream()
                .map(MessageTemplateVariableVO::getKey)
                .collect(Collectors.toSet());
        Set<String> variables = extractVariables(titleTemplate, contentTemplate);
        List<String> invalidVariables = variables.stream()
                .filter(item -> !supportedKeys.contains(item))
                .sorted()
                .toList();
        if (!invalidVariables.isEmpty()) {
            throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_VARIABLE_INVALID,
                    "存在不支持的模板变量: " + String.join(", ", invalidVariables));
        }
    }

    private Set<String> extractVariables(String... texts) {
        return java.util.Arrays.stream(texts)
                .filter(StringUtils::hasText)
                .flatMap(text -> {
                    List<String> variables = new ArrayList<>();
                    Matcher matcher = VARIABLE_PATTERN.matcher(text);
                    while (matcher.find()) {
                        variables.add(matcher.group(1));
                    }
                    return variables.stream();
                })
                .collect(Collectors.toSet());
    }

    private MessageTemplateMetadataVO getRequiredDefinition(String templateType) {
        if (!MessageTemplateTypeEnum.isValid(templateType)) {
            throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_TYPE_INVALID);
        }
        MessageTemplateMetadataVO metadata = definitionMap().get(templateType);
        if (metadata == null) {
            throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_TYPE_INVALID);
        }
        return metadata;
    }

    private Map<String, MessageTemplateMetadataVO> definitionMap() {
        Map<String, MessageTemplateMetadataVO> definitions = new LinkedHashMap<>();
        definitions.put(MessageTemplateTypeEnum.WORKFLOW_COMPLETED.getCode(), workflowCompletedMetadata());
        definitions.put(MessageTemplateTypeEnum.WORKFLOW_FAILED.getCode(), workflowFailedMetadata());
        definitions.put(MessageTemplateTypeEnum.HUMAN_REVIEW_PENDING.getCode(), humanReviewPendingMetadata());
        definitions.put(MessageTemplateTypeEnum.APPROVAL_RESULT.getCode(), approvalResultMetadata());
        definitions.put(MessageTemplateTypeEnum.SYSTEM_ALERT.getCode(), systemAlertMetadata());
        definitions.put(MessageTemplateTypeEnum.PROJECT_SYNC_SUMMARY.getCode(), projectSyncSummaryMetadata());
        definitions.put(MessageTemplateTypeEnum.REVIEW_COMPLETED.getCode(), reviewCompletedMetadata());
        definitions.put(MessageTemplateTypeEnum.REVIEW_REMIND.getCode(), reviewRemindMetadata());
        definitions.put(MessageTemplateTypeEnum.REVIEW_ESCALATE.getCode(), reviewEscalateMetadata());
        return definitions;
    }

    private MessageTemplateMetadataVO workflowCompletedMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.WORKFLOW_COMPLETED.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.WORKFLOW_COMPLETED.getDescription());
        metadata.setDefaultTitleTemplate("工作流《${workflowTemplateName}》执行完成");
        metadata.setDefaultContentTemplate("""
工作流实例：${workflowInstanceName}
执行状态：${workflowStatus}
关联 Spec：${specTitle}
完成时间：${completedAt}
当前节点：${currentNodeLabel}
预览链接：${previewUrl}
""".trim());
        metadata.setSupportedVariables(List.of(
                variable("workflowTemplateName", "工作流名称", "工作流模板名称", "标准研发工作流"),
                variable("workflowInstanceName", "实例名称", "工作流实例名称", "SchemaPlexAI 渠道通知测试"),
                variable("workflowStatus", "执行状态", "工作流当前状态", CommonConstant.STATUS_ACTIVE),
                variable("specTitle", "Spec 标题", "关联 Spec 标题，没有时返回 -", "AI工作平台渠道接入能力增强"),
                variable("specId", "Spec ID", "关联 Spec 的主键 ID", "spec-001"),
                variable("currentNodeLabel", "当前节点", "触发通知时的节点名称", "通信渠道"),
                variable("startedAt", "开始时间", "工作流开始时间", "2026-04-04 09:30:00"),
                variable("completedAt", "完成时间", "工作流完成时间", "2026-04-04 10:15:00"),
                variable("previewUrl", "预览链接", "工作流实例详情页链接", "http://localhost:5173/workflow/instance-id"),
                variable("specPreviewUrl", "Spec 预览链接", "Spec 详情页链接，没有时返回 -", "http://localhost:5173/spec/spec-id")
        ));
        return metadata;
    }

    private MessageTemplateMetadataVO workflowFailedMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.WORKFLOW_FAILED.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.WORKFLOW_FAILED.getDescription());
        metadata.setDefaultTitleTemplate("工作流《${workflowTemplateName}》执行失败");
        metadata.setDefaultContentTemplate("""
## 执行异常提醒

- 工作流实例：${workflowInstanceName}
- 当前状态：${workflowStatus}
- 失败节点：${currentNodeLabel}
- 开始时间：${startedAt}
- 详情链接：${previewUrl}
""".trim());
        metadata.setSupportedVariables(baseWorkflowVariables());
        return metadata;
    }

    private MessageTemplateMetadataVO humanReviewPendingMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.HUMAN_REVIEW_PENDING.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.HUMAN_REVIEW_PENDING.getDescription());
        metadata.setDefaultTitleTemplate("工作流《${workflowTemplateName}》待人工审核");
        metadata.setDefaultContentTemplate("""
## 审核待办

- 工作流实例：${workflowInstanceName}
- 审核节点：${currentNodeLabel}
- 关联 Spec：${specTitle}
- 处理入口：${previewUrl}
""".trim());
        metadata.setSupportedVariables(baseWorkflowVariables());
        return metadata;
    }

    private MessageTemplateMetadataVO approvalResultMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.APPROVAL_RESULT.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.APPROVAL_RESULT.getDescription());
        metadata.setDefaultTitleTemplate("审批结果通知：${workflowTemplateName}");
        metadata.setDefaultContentTemplate("""
## 审批反馈

- 业务标题：${specTitle}
- 结果状态：${workflowStatus}
- 当前节点：${currentNodeLabel}
- 查看详情：${previewUrl}
""".trim());
        metadata.setSupportedVariables(baseWorkflowVariables());
        return metadata;
    }

    private MessageTemplateMetadataVO systemAlertMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.SYSTEM_ALERT.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.SYSTEM_ALERT.getDescription());
        metadata.setDefaultTitleTemplate("系统告警：${workflowTemplateName}");
        metadata.setDefaultContentTemplate("""
## 告警详情

- 关联流程：${workflowTemplateName}
- 当前状态：${workflowStatus}
- 触发节点：${currentNodeLabel}
- 排查入口：${previewUrl}
""".trim());
        metadata.setSupportedVariables(baseWorkflowVariables());
        return metadata;
    }

    private MessageTemplateMetadataVO projectSyncSummaryMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.PROJECT_SYNC_SUMMARY.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.PROJECT_SYNC_SUMMARY.getDescription());
        metadata.setDefaultTitleTemplate("项目同步摘要：${specTitle}");
        metadata.setDefaultContentTemplate("""
## 同步摘要

- 关联流程：${workflowTemplateName}
- 执行实例：${workflowInstanceName}
- 最新状态：${workflowStatus}
- 完成时间：${completedAt}
- 查看详情：${previewUrl}
""".trim());
        metadata.setSupportedVariables(baseWorkflowVariables());
        return metadata;
    }

    private MessageTemplateMetadataVO reviewCompletedMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.REVIEW_COMPLETED.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.REVIEW_COMPLETED.getDescription());
        metadata.setDefaultTitleTemplate("评审已完成");
        metadata.setDefaultContentTemplate("评审会话「${sessionTitle}」已完成，最终决策：${decision}");
        metadata.setSupportedVariables(List.of(
                variable("sessionTitle", "评审会话标题", "评审会话的标题", "API 接口设计评审"),
                variable("decision", "评审决策", "最终评审决策结果", "approved")
        ));
        return metadata;
    }

    private MessageTemplateMetadataVO reviewRemindMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.REVIEW_REMIND.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.REVIEW_REMIND.getDescription());
        metadata.setDefaultTitleTemplate("评审催办提醒");
        metadata.setDefaultContentTemplate("评审会话「${sessionTitle}」已超过截止时间，请尽快提交评审意见。");
        metadata.setSupportedVariables(List.of(
                variable("sessionTitle", "评审会话标题", "评审会话的标题", "API 接口设计评审"),
                variable("deadline", "截止时间", "评审截止时间", "2026-04-20 18:00:00")
        ));
        return metadata;
    }

    private MessageTemplateMetadataVO reviewEscalateMetadata() {
        MessageTemplateMetadataVO metadata = new MessageTemplateMetadataVO();
        metadata.setTemplateType(MessageTemplateTypeEnum.REVIEW_ESCALATE.getCode());
        metadata.setTemplateName(MessageTemplateTypeEnum.REVIEW_ESCALATE.getDescription());
        metadata.setDefaultTitleTemplate("评审超时升级");
        metadata.setDefaultContentTemplate("评审会话「${sessionTitle}」已超时且未完成，已升级处理。");
        metadata.setSupportedVariables(List.of(
                variable("sessionTitle", "评审会话标题", "评审会话的标题", "API 接口设计评审")
        ));
        return metadata;
    }

    private List<MessageTemplateVariableVO> baseWorkflowVariables() {
        return List.of(
                variable("workflowTemplateName", "工作流名称", "工作流模板名称", "标准研发工作流"),
                variable("workflowInstanceName", "实例名称", "工作流实例名称", "SchemaPlexAI 渠道通知测试"),
                variable("workflowStatus", "执行状态", "工作流当前状态", CommonConstant.STATUS_ACTIVE),
                variable("specTitle", "Spec 标题", "关联 Spec 标题，没有时返回 -", "AI工作平台渠道接入能力增强"),
                variable("specId", "Spec ID", "关联 Spec 的主键 ID", "spec-001"),
                variable("currentNodeLabel", "当前节点", "触发通知时的节点名称", "通信渠道"),
                variable("startedAt", "开始时间", "工作流开始时间", "2026-04-04 09:30:00"),
                variable("completedAt", "完成时间", "工作流完成时间", "2026-04-04 10:15:00"),
                variable("previewUrl", "预览链接", "工作流实例详情页链接", "http://localhost:5173/workflow/instance-id"),
                variable("specPreviewUrl", "Spec 预览链接", "Spec 详情页链接，没有时返回 -", "http://localhost:5173/spec/spec-id")
        );
    }

    private MessageTemplateVariableVO variable(String key, String label, String description, String sampleValue) {
        MessageTemplateVariableVO variable = new MessageTemplateVariableVO();
        variable.setKey(key);
        variable.setLabel(label);
        variable.setDescription(description);
        variable.setSampleValue(sampleValue);
        return variable;
    }

    private void validateSupportedChannels(List<String> supportedChannels) {
        if (supportedChannels == null) {
            return;
        }
        List<String> invalidChannels = supportedChannels.stream()
                .filter(StringUtils::hasText)
                .filter(channel -> !NotificationChannelTypeEnum.isValid(channel))
                .distinct()
                .toList();
        if (!invalidChannels.isEmpty()) {
            throw new BusinessException(ResultCode.MESSAGE_TEMPLATE_CHANNEL_INVALID,
                    "存在不支持的渠道类型: " + String.join(", ", invalidChannels));
        }
    }

    private Set<String> resolveCreatorIds(String createdByKeyword) {
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .and(wrapper -> wrapper.like(User::getRealName, createdByKeyword)
                        .or()
                        .like(User::getUsername, createdByKeyword)));
        if (users == null || users.isEmpty()) {
            return Collections.emptySet();
        }
        return users.stream()
                .map(User::getId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private List<MessageTemplateVO> enrichTemplateVOs(List<MessageTemplateVO> templates, List<MessageTemplate> entities) {
        if (templates == null || templates.isEmpty() || entities == null || entities.isEmpty()) {
            return templates;
        }
        Map<String, String> creatorNames = resolveCreatorNames(entities.stream()
                .map(MessageTemplate::getCreatedBy)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet()));
        for (int index = 0; index < templates.size() && index < entities.size(); index++) {
            enrichTemplateVO(templates.get(index), entities.get(index), creatorNames);
        }
        return templates;
    }

    private MessageTemplateVO enrichTemplateVO(MessageTemplateVO template, MessageTemplate entity) {
        return enrichTemplateVO(template, entity, resolveCreatorNames(
                entity != null && StringUtils.hasText(entity.getCreatedBy())
                        ? Set.of(entity.getCreatedBy())
                        : Set.of()
        ));
    }

    private MessageTemplateVO enrichTemplateVO(MessageTemplateVO template,
                                               MessageTemplate entity,
                                               Map<String, String> creatorNames) {
        if (template == null || entity == null) {
            return template;
        }
        template.setCreatedBy(entity.getCreatedBy());
        template.setSupportedChannels(entity.getSupportedChannels());
        template.setCreatedByName(creatorNames.get(entity.getCreatedBy()));
        return template;
    }

    private Map<String, String> resolveCreatorNames(Collection<String> creatorIds) {
        Set<String> filteredIds = creatorIds == null
                ? Set.of()
                : creatorIds.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        if (filteredIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(filteredIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        User::getId,
                        user -> StringUtils.hasText(user.getRealName()) ? user.getRealName() : user.getUsername(),
                        (left, right) -> left
                ));
    }
}
