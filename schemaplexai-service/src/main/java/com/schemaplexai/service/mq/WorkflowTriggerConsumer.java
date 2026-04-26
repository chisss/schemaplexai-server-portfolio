package com.schemaplexai.service.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.service.mq.message.WorkflowTriggerMessage;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.runtime.SpecWorkflowRuntimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final SpecWorkflowRuntimeService specWorkflowRuntimeService;

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

        String originalUserId = SecurityUtil.getCurrentUserId();
        String originalTenantId = SecurityUtil.getCurrentTenantId();
        String originalUsername = SecurityUtil.getCurrentUsername();
        applySecurityContext(message);
        try {
            String templateId = resolveTemplateId(message);
            if (!StringUtils.hasText(templateId)) {
                log.warn("未找到匹配的工作流模板: specId={}, triggerType={}", specId, triggerType);
                return;
            }
            WorkflowTemplate template = workflowTemplateMapper.selectById(templateId);
            if (template == null) {
                log.warn("工作流模板不存在，跳过事件触发: specId={}, templateId={}", specId, templateId);
                return;
            }
            if (!isEventTriggerEnabled(template)) {
                log.info("事件触发未启用，跳过工作流创建: specId={}, templateId={}", specId, templateId);
                return;
            }

            if (hasActiveInstance(specId, templateId)) {
                log.info("存在运行中/待启动实例，跳过重复创建: specId={}, templateId={}", specId, templateId);
                return;
            }

            Spec spec = specMapper.selectById(specId);
            if (spec == null) {
                log.warn("Spec不存在，跳过工作流触发: specId={}", specId);
                return;
            }

            var request = new WorkflowInstanceCreateRequest();
            request.setTemplateId(templateId);
            request.setTenantId(message.getTenantId());
            request.setSpecId(specId);
            request.setName("Spec审核工作流-" + spec.getName());
            request.setVariables(buildVariables(spec, message));

            var instance = workflowInstanceService.create(request);
            Spec update = new Spec();
            update.setId(specId);
            update.setWorkflowInstanceId(instance.getId());
            update.setTargetBranch(specWorkflowRuntimeService.resolveTargetBranch(spec));
            specMapper.updateById(update);
            workflowInstanceService.start(instance.getId());

            log.info("工作流实例已创建并启动: instanceId={}, specId={}, templateId={}",
                    instance.getId(), specId, templateId);

        } catch (Exception e) {
            log.error("处理工作流触发消息失败: specId={}, triggerType={}", specId, triggerType, e);
        } finally {
            restoreSecurityContext(originalUserId, originalTenantId, originalUsername);
        }
    }

    private void applySecurityContext(WorkflowTriggerMessage message) {
        if (StringUtils.hasText(message.getTriggerBy())) {
            SecurityUtil.setCurrentUserId(message.getTriggerBy());
        }
        if (StringUtils.hasText(message.getTenantId())) {
            SecurityUtil.setCurrentTenantId(message.getTenantId());
        }
        if (!StringUtils.hasText(SecurityUtil.getCurrentUsername())) {
            SecurityUtil.setCurrentUsername("workflow-trigger");
        }
    }

    private void restoreSecurityContext(String userId, String tenantId, String username) {
        SecurityUtil.clear();
        if (StringUtils.hasText(userId)) {
            SecurityUtil.setCurrentUserId(userId);
        }
        if (StringUtils.hasText(tenantId)) {
            SecurityUtil.setCurrentTenantId(tenantId);
        }
        if (StringUtils.hasText(username)) {
            SecurityUtil.setCurrentUsername(username);
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
        List<WorkflowInstance> instances = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getSpecId, specId)
                        .eq(WorkflowInstance::getTemplateId, templateId)
                        .in(WorkflowInstance::getStatus,
                                WorkflowInstanceStatusEnum.PENDING.getCode(),
                                WorkflowInstanceStatusEnum.RUNNING.getCode(),
                                WorkflowInstanceStatusEnum.PAUSED.getCode())
                        .orderByAsc(WorkflowInstance::getCreatedAt)
        );
        if (instances == null || instances.isEmpty()) {
            return false;
        }

        for (WorkflowInstance instance : instances) {
            if (!repairInconsistentActiveInstance(instance)) {
                return true;
            }
        }
        return false;
    }

    private boolean repairInconsistentActiveInstance(WorkflowInstance instance) {
        List<WorkflowNodeExecution> nodeExecutions = workflowNodeExecutionMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, instance.getId())
                        .orderByAsc(WorkflowNodeExecution::getCreatedAt)
        );
        if (nodeExecutions == null || nodeExecutions.isEmpty()) {
            return false;
        }

        boolean hasFailedNode = nodeExecutions.stream()
                .anyMatch(node -> WorkflowInstanceStatusEnum.FAILED.getCode().equals(node.getStatus()));
        boolean hasCompletedEndNode = nodeExecutions.stream()
                .anyMatch(node -> "end".equals(node.getNodeId())
                        && WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(node.getStatus()));
        if (!hasFailedNode && !hasCompletedEndNode) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        WorkflowInstance update = new WorkflowInstance();
        update.setId(instance.getId());
        update.setUpdatedAt(now);

        if (hasCompletedEndNode) {
            update.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
            update.setCurrentNodeId("end");
            update.setCompletedAt(instance.getCompletedAt() != null ? instance.getCompletedAt() : now);
            log.warn("检测到状态残留的已完成实例，已自动修复: instanceId={}", instance.getId());
        } else {
            update.setStatus(WorkflowInstanceStatusEnum.FAILED.getCode());
            update.setCompletedAt(now);
            log.warn("检测到状态残留的失败实例，已自动修复: instanceId={}", instance.getId());
        }

        workflowInstanceMapper.updateById(update);
        return true;
    }

    private Map<String, Object> buildVariables(Spec spec, WorkflowTriggerMessage message) {
        Map<String, Object> variables = new HashMap<>(specWorkflowRuntimeService.buildRuntimeVariables(spec, message));
        variables.put("triggerType", message.getTriggerType());
        variables.put("docType", message.getDocType());
        variables.put("triggerBy", message.getTriggerBy());
        variables.put("triggeredAt", message.getTriggeredAt() != null ? message.getTriggeredAt().toString() : null);
        variables.put("requestId", message.getRequestId());
        variables.put("tenantId", message.getTenantId());
        variables.entrySet().removeIf(entry -> entry.getValue() == null);
        return variables;
    }

    private boolean isEventTriggerEnabled(WorkflowTemplate template) {
        Map<String, Object> triggerNode = findTriggerNode(template);
        if (triggerNode == null || !"trigger_event".equals(readString(triggerNode, "type"))) {
            return true;
        }
        Map<String, Object> config = getConfig(triggerNode);
        return Boolean.TRUE.equals(config.get("enabled"));
    }

    private Map<String, Object> findTriggerNode(WorkflowTemplate template) {
        if (template == null || template.getDefinition() == null) {
            return null;
        }
        Object rawNodes = template.getDefinition().get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) {
            return null;
        }
        for (Object rawNode : nodes) {
            if (rawNode instanceof Map<?, ?> rawMap) {
                Map<String, Object> node = new LinkedHashMap<>();
                rawMap.forEach((key, value) -> node.put(String.valueOf(key), value));
                String type = readString(node, "type");
                if (type.startsWith("trigger_") || "start".equals(type)) {
                    return node;
                }
            }
        }
        return null;
    }

    private Map<String, Object> getConfig(Map<String, Object> node) {
        Object rawConfig = node.get("config");
        if (rawConfig instanceof Map<?, ?> rawMap) {
            Map<String, Object> config = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> config.put(String.valueOf(key), value));
            return config;
        }
        return Map.of();
    }

    private String readString(Map<String, Object> data, String key) {
        if (data == null || !StringUtils.hasText(key)) {
            return "";
        }
        Object value = data.get(key);
        return value != null ? String.valueOf(value) : "";
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
