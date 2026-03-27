package com.schemaplexai.service.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.service.mq.message.WorkflowTriggerMessage;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 工作流触发消息消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowTriggerConsumer {

    private final WorkflowInstanceService workflowInstanceService;
    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final SpecMapper specMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;

    @RabbitListener(queues = "sf.workflow.trigger")
    public void handleWorkflowTrigger(WorkflowTriggerMessage message) {
        if (message == null || !StringUtils.hasText(message.getSpecId())) {
            log.warn("收到无效工作流触发消息: {}", message);
            return;
        }

        String specId = message.getSpecId();
        String triggerType = message.getTriggerType();
        log.info("收到工作流触发消息: specId={}, triggerType={}, requestId={}",
                specId, triggerType, message.getRequestId());

        try {
            String templateId = resolveTemplateId(message);
            if (!StringUtils.hasText(templateId)) {
                log.warn("未找到匹配的工作流模板: specId={}, triggerType={}", specId, triggerType);
                return;
            }

            if (hasActiveInstance(specId, templateId)) {
                log.info("存在运行中/待启动实例，跳过重复创建: specId={}, templateId={}", specId, templateId);
                return;
            }

            var request = new WorkflowInstanceCreateRequest();
            request.setTemplateId(templateId);
            request.setSpecId(specId);
            request.setName("Spec审核工作流-" + specId);
            request.setVariables(buildVariables(message));

            var instance = workflowInstanceService.create(request);
            workflowInstanceService.start(instance.getId());

            log.info("工作流实例已创建并启动: instanceId={}, specId={}, templateId={}",
                    instance.getId(), specId, templateId);

        } catch (Exception e) {
            log.error("处理工作流触发消息失败: specId={}, triggerType={}", specId, triggerType, e);
        }
    }

    private String resolveTemplateId(WorkflowTriggerMessage message) {
        if (StringUtils.hasText(message.getWorkflowTemplateId())) {
            return message.getWorkflowTemplateId();
        }

        Spec spec = specMapper.selectById(message.getSpecId());
        if (spec != null && StringUtils.hasText(spec.getWorkflowId())) {
            return spec.getWorkflowId();
        }

        return findTemplateByTriggerType(message.getTriggerType());
    }

    private boolean hasActiveInstance(String specId, String templateId) {
        Long count = workflowInstanceMapper.selectCount(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getSpecId, specId)
                        .eq(WorkflowInstance::getTemplateId, templateId)
                        .in(WorkflowInstance::getStatus,
                                WorkflowInstanceStatusEnum.PENDING.getCode(),
                                WorkflowInstanceStatusEnum.RUNNING.getCode(),
                                WorkflowInstanceStatusEnum.PAUSED.getCode())
        );
        return count != null && count > 0;
    }

    private Map<String, Object> buildVariables(WorkflowTriggerMessage message) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("triggerType", message.getTriggerType());
        variables.put("docType", message.getDocType());
        variables.put("triggerBy", message.getTriggerBy());
        variables.put("triggeredAt", message.getTriggeredAt() != null ? message.getTriggeredAt().toString() : null);
        variables.put("requestId", message.getRequestId());
        variables.put("tenantId", message.getTenantId());
        variables.entrySet().removeIf(entry -> entry.getValue() == null);
        return variables;
    }

    private String findTemplateByTriggerType(String triggerType) {
        if (!StringUtils.hasText(triggerType)) {
            return null;
        }

        String category = switch (triggerType) {
            case "spec-review" -> "feature-development";
            case "bug-fix" -> "bug-fix";
            case "refactoring" -> "refactoring";
            default -> null;
        };

        if (category == null) {
            return null;
        }

        WorkflowTemplate template = workflowTemplateMapper.selectOne(
                new LambdaQueryWrapper<WorkflowTemplate>()
                        .eq(WorkflowTemplate::getCategory, category)
                        .eq(WorkflowTemplate::getIsBuiltin, true)
                        .last("LIMIT 1")
        );

        return template != null ? template.getId() : null;
    }
}
