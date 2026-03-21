package com.schemaplexai.service.workflow.validator;

import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 工作流业务校验器
 */
@Component
@RequiredArgsConstructor
public class WorkflowValidator {

    /**
     * 状态转换规则：key=当前状态，value=允许转换的目标状态集合
     * CANCELLED/COMPLETED 为终态，不允许再转换
     */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            WorkflowInstanceStatusEnum.PENDING.getCode(),
            Set.of(WorkflowInstanceStatusEnum.RUNNING.getCode()),

            WorkflowInstanceStatusEnum.RUNNING.getCode(),
            Set.of(WorkflowInstanceStatusEnum.PAUSED.getCode(),
                    WorkflowInstanceStatusEnum.COMPLETED.getCode(),
                    WorkflowInstanceStatusEnum.FAILED.getCode(),
                    WorkflowInstanceStatusEnum.CANCELLED.getCode()),

            WorkflowInstanceStatusEnum.PAUSED.getCode(),
            Set.of(WorkflowInstanceStatusEnum.RUNNING.getCode(),
                    WorkflowInstanceStatusEnum.FAILED.getCode(),
                    WorkflowInstanceStatusEnum.CANCELLED.getCode()),

            WorkflowInstanceStatusEnum.FAILED.getCode(),
            Set.of(WorkflowInstanceStatusEnum.RUNNING.getCode())
    );

    /**
     * 校验状态转换合法性
     */
    public void validateStatusTransition(String currentStatus, String targetStatus) {
        var allowed = ALLOWED_TRANSITIONS.get(currentStatus);
        if (allowed == null || !allowed.contains(targetStatus)) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
    }

    /**
     * 校验模板非内置（可编辑/删除）
     */
    public void validateNotBuiltin(WorkflowTemplate template) {
        if (Boolean.TRUE.equals(template.getIsBuiltin())) {
            throw new BusinessException(ResultCode.WORKFLOW_BUILTIN_READONLY);
        }
    }

    /**
     * 校验 definition 格式基本有效性
     */
    @SuppressWarnings("unchecked")
    public void validateDefinitionFormat(Map<String, Object> definition) {
        if (definition == null) {
            throw new BusinessException(ResultCode.WORKFLOW_DEFINITION_INVALID);
        }
        if (!definition.containsKey("nodes") || !definition.containsKey("edges")) {
            throw new BusinessException(ResultCode.WORKFLOW_DEFINITION_INVALID);
        }
        var nodes = definition.get("nodes");
        if (!(nodes instanceof java.util.List)) {
            throw new BusinessException(ResultCode.WORKFLOW_DEFINITION_INVALID);
        }
    }

    /**
     * 校验实例可启动（仅 pending 状态允许，FAILED/CANCELLED 为终态不可重启）
     */
    public void validateCanStart(WorkflowInstance instance) {
        var status = instance.getStatus();
        if (!WorkflowInstanceStatusEnum.PENDING.getCode().equals(status)) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED);
        }
    }
}
