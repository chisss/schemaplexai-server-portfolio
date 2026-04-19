package com.schemaplexai.service.workflow.flowable;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.task.api.Task;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * Flowable 运行时 API 封装层
 *
 * <p>提供启动/暂停/恢复/终止/信号/完成任务等操作，
 * 隔离 Flowable API 细节，供 WorkflowInstanceServiceImpl 和 WorkflowNodeEngine 调用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlowableWorkflowBridge {

    private final RuntimeService runtimeService;
    private final TaskService taskService;

    /**
     * 启动 Flowable 流程实例
     *
     * @param processDefinitionId Flowable 流程定义ID
     * @param businessKey         业务主键（sf_workflow_instance.id）
     * @param variables           流程变量
     * @return Flowable 流程实例ID
     */
    public String startProcess(String processDefinitionId, String businessKey, Map<String, Object> variables) {
        ProcessInstance pi = runtimeService.startProcessInstanceById(
                processDefinitionId, businessKey, variables);
        log.info("Flowable 流程实例已启动: processInstanceId={}, businessKey={}, processDefinitionId={}",
                pi.getId(), businessKey, processDefinitionId);
        return pi.getId();
    }

    /**
     * 暂停流程实例
     */
    public void suspendProcess(String processInstanceId) {
        if (!StringUtils.hasText(processInstanceId)) {
            return;
        }
        try {
            runtimeService.suspendProcessInstanceById(processInstanceId);
            log.info("Flowable 流程实例已暂停: processInstanceId={}", processInstanceId);
        } catch (Exception e) {
            log.warn("暂停 Flowable 流程实例失败: processInstanceId={}, error={}", processInstanceId, e.getMessage());
        }
    }

    /**
     * 恢复（激活）流程实例
     */
    public void activateProcess(String processInstanceId) {
        if (!StringUtils.hasText(processInstanceId)) {
            return;
        }
        try {
            runtimeService.activateProcessInstanceById(processInstanceId);
            log.info("Flowable 流程实例已恢复: processInstanceId={}", processInstanceId);
        } catch (Exception e) {
            log.warn("恢复 Flowable 流程实例失败: processInstanceId={}, error={}", processInstanceId, e.getMessage());
        }
    }

    /**
     * 删除（终止）流程实例
     */
    public void deleteProcess(String processInstanceId, String reason) {
        if (!StringUtils.hasText(processInstanceId)) {
            return;
        }
        try {
            runtimeService.deleteProcessInstance(processInstanceId, reason);
            log.info("Flowable 流程实例已删除: processInstanceId={}, reason={}", processInstanceId, reason);
        } catch (Exception e) {
            log.warn("删除 Flowable 流程实例失败: processInstanceId={}, error={}", processInstanceId, e.getMessage());
        }
    }

    /**
     * 触发 ReceiveTask 继续执行（agent/document 节点完成后调用）
     */
    public boolean triggerReceiveTask(String processInstanceId, String activityId) {
        if (!StringUtils.hasText(processInstanceId)) {
            return false;
        }
        try {
            Execution execution = runtimeService.createExecutionQuery()
                    .processInstanceId(processInstanceId)
                    .activityId(activityId)
                    .singleResult();
            if (execution != null) {
                runtimeService.trigger(execution.getId());
                log.info("ReceiveTask 已触发继续: processInstanceId={}, activityId={}", processInstanceId, activityId);
                return true;
            } else {
                log.warn("未找到 ReceiveTask 执行实例: processInstanceId={}, activityId={}", processInstanceId, activityId);
                return false;
            }
        } catch (Exception e) {
            log.warn("触发 ReceiveTask 失败: processInstanceId={}, activityId={}, error={}",
                    processInstanceId, activityId, e.getMessage());
            return false;
        }
    }

    /**
     * 完成 UserTask（审批通过后调用）
     */
    public void completeUserTask(String processInstanceId, String taskDefinitionKey, Map<String, Object> variables) {
        if (!StringUtils.hasText(processInstanceId)) {
            return;
        }
        try {
            Task task = taskService.createTaskQuery()
                    .processInstanceId(processInstanceId)
                    .taskDefinitionKey(taskDefinitionKey)
                    .singleResult();
            if (task != null) {
                taskService.complete(task.getId(), variables);
                log.info("UserTask 已完成: processInstanceId={}, taskDefinitionKey={}, taskId={}",
                        processInstanceId, taskDefinitionKey, task.getId());
            } else {
                log.warn("未找到 UserTask: processInstanceId={}, taskDefinitionKey={}",
                        processInstanceId, taskDefinitionKey);
            }
        } catch (Exception e) {
            log.warn("完成 UserTask 失败: processInstanceId={}, taskDefinitionKey={}, error={}",
                    processInstanceId, taskDefinitionKey, e.getMessage());
        }
    }

    /**
     * 流程活动状态回滚（审批拒绝时调用）
     *
     * <p>使用 Flowable 的 ChangeActivityState API 将当前活动节点移动到目标节点。
     */
    public void moveActivityState(String processInstanceId, String currentActivityId, String targetActivityId) {
        if (!StringUtils.hasText(processInstanceId)) {
            return;
        }
        try {
            runtimeService.createChangeActivityStateBuilder()
                    .processInstanceId(processInstanceId)
                    .moveActivityIdTo(currentActivityId, targetActivityId)
                    .changeState();
            log.info("活动状态回滚成功: processInstanceId={}, from={}, to={}",
                    processInstanceId, currentActivityId, targetActivityId);
        } catch (Exception e) {
            log.warn("活动状态回滚失败，降级为删除重建: processInstanceId={}, from={}, to={}, error={}",
                    processInstanceId, currentActivityId, targetActivityId, e.getMessage());
            // 降级处理：对于 UserTask 回滚到 ReceiveTask 等复杂场景，
            // Flowable 的 ChangeActivityState 可能不支持，此时不做 Flowable 层面的回滚，
            // 由 WorkflowNodeEngine 自行驱动节点重执行
        }
    }

    /**
     * 检查流程实例是否存在且未结束
     */
    public boolean isProcessActive(String processInstanceId) {
        if (!StringUtils.hasText(processInstanceId)) {
            return false;
        }
        try {
            ProcessInstance pi = runtimeService.createProcessInstanceQuery()
                    .processInstanceId(processInstanceId)
                    .singleResult();
            return pi != null && !pi.isEnded();
        } catch (Exception e) {
            return false;
        }
    }
}
