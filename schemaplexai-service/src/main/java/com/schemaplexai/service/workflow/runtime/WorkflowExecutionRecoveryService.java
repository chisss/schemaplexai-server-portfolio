package com.schemaplexai.service.workflow.runtime;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工作流执行恢复服务
 *
 * <p>用于兜底修复以下异常状态：
 * <ul>
 *   <li>AgentExecution 已完成/失败，但 WorkflowNodeExecution 仍停留在 pending/running</li>
 *   <li>工作流实例仍处于 running/paused，未收到 Agent 完成回调</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowExecutionRecoveryService {

    private static final int MAX_RECOVERY_BATCH = 200;
    private static final Set<String> RECOVERABLE_INSTANCE_STATUS = Set.of(
            WorkflowInstanceStatusEnum.RUNNING.getCode(),
            WorkflowInstanceStatusEnum.PAUSED.getCode()
    );
    private static final Set<String> RECOVERABLE_NODE_STATUS = Set.of(
            WorkflowInstanceStatusEnum.PENDING.getCode(),
            WorkflowInstanceStatusEnum.RUNNING.getCode()
    );
    private static final Set<String> TERMINAL_AGENT_STATUS = Set.of(
            AgentExecutionStatusEnum.COMPLETED.getCode(),
            AgentExecutionStatusEnum.STOPPED.getCode(),
            AgentExecutionStatusEnum.FAILED.getCode(),
            AgentExecutionStatusEnum.PAUSED.getCode()
    );

    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final AgentExecutionMapper agentExecutionMapper;
    private final WorkflowNodeEngine workflowNodeEngine;

    /**
     * 恢复已脱节的 Agent 回调。
     *
     * @param limit 单次最多修复条数
     * @return 成功触发恢复的节点数量
     */
    public int recoverDetachedAgentCallbacks(int limit) {
        int batchSize = Math.min(Math.max(limit, 1), MAX_RECOVERY_BATCH);
        int recoveredCount = recoverDetachedAgentCallbacksInternal(batchSize);
        recoveredCount += recoverCompletedNodeTransitions(batchSize);
        recoveredCount += recoverPendingCurrentNodes(batchSize);
        return recoveredCount;
    }

    private int recoverDetachedAgentCallbacksInternal(int batchSize) {
        List<WorkflowNodeExecution> candidateNodes = workflowNodeExecutionMapper.selectList(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .in(WorkflowNodeExecution::getStatus, RECOVERABLE_NODE_STATUS)
                        .isNotNull(WorkflowNodeExecution::getAgentExecutionId)
                        .orderByAsc(WorkflowNodeExecution::getCreatedAt)
                        .last("limit " + batchSize)
        );
        if (candidateNodes.isEmpty()) {
            return 0;
        }

        Map<String, WorkflowInstance> instanceMap = workflowInstanceMapper.selectBatchIds(
                        candidateNodes.stream()
                                .map(WorkflowNodeExecution::getInstanceId)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(WorkflowInstance::getId, Function.identity()));

        Map<String, AgentExecution> agentExecutionMap = agentExecutionMapper.selectBatchIds(
                        candidateNodes.stream()
                                .map(WorkflowNodeExecution::getAgentExecutionId)
                                .filter(StringUtils::hasText)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(AgentExecution::getId, Function.identity()));

        int recoveredCount = 0;
        for (WorkflowNodeExecution nodeExecution : candidateNodes) {
            WorkflowInstance instance = instanceMap.get(nodeExecution.getInstanceId());
            if (instance == null || !RECOVERABLE_INSTANCE_STATUS.contains(instance.getStatus())) {
                continue;
            }

            AgentExecution agentExecution = agentExecutionMap.get(nodeExecution.getAgentExecutionId());
            if (agentExecution == null || !TERMINAL_AGENT_STATUS.contains(agentExecution.getStatus())) {
                continue;
            }

            String callbackPayload = resolveCallbackPayload(agentExecution);
            try {
                log.warn("检测到脱节的 Agent 回调，开始自动恢复: instanceId={}, nodeId={}, agentExecutionId={}, agentStatus={}",
                        instance.getId(), nodeExecution.getNodeId(), agentExecution.getId(), agentExecution.getStatus());
                workflowNodeEngine.onAgentNodeCompleted(
                        instance.getId(),
                        nodeExecution.getNodeId(),
                        agentExecution.getStatus(),
                        callbackPayload
                );
                recoveredCount++;
            } catch (Exception exception) {
                log.error("恢复脱节 Agent 回调失败: instanceId={}, nodeId={}, agentExecutionId={}",
                        instance.getId(), nodeExecution.getNodeId(), agentExecution.getId(), exception);
            }
        }
        return recoveredCount;
    }

    private int recoverCompletedNodeTransitions(int batchSize) {
        List<WorkflowInstance> activeInstances = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, RECOVERABLE_INSTANCE_STATUS)
                        .isNotNull(WorkflowInstance::getCurrentNodeId)
                        .orderByAsc(WorkflowInstance::getUpdatedAt)
                        .last("limit " + batchSize)
        );
        if (activeInstances.isEmpty()) {
            return 0;
        }

        int recoveredCount = 0;
        for (WorkflowInstance instance : activeInstances) {
            List<WorkflowNodeExecution> nodeExecutions = workflowNodeExecutionMapper.selectList(
                    new LambdaQueryWrapper<WorkflowNodeExecution>()
                            .eq(WorkflowNodeExecution::getInstanceId, instance.getId())
                            .orderByAsc(WorkflowNodeExecution::getCreatedAt)
            );
            if (nodeExecutions.isEmpty()) {
                continue;
            }
            boolean hasRunningNode = nodeExecutions.stream()
                    .anyMatch(node -> WorkflowInstanceStatusEnum.RUNNING.getCode().equals(node.getStatus()));
            if (hasRunningNode) {
                continue;
            }

            WorkflowNodeExecution currentNodeExecution = nodeExecutions.stream()
                    .filter(node -> instance.getCurrentNodeId().equals(node.getNodeId()))
                    .findFirst()
                    .orElse(null);
            if (currentNodeExecution == null
                    || !WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(currentNodeExecution.getStatus())) {
                continue;
            }

            try {
                log.warn("检测到已完成节点未推进后继节点，开始自动补推进: instanceId={}, nodeId={}",
                        instance.getId(), currentNodeExecution.getNodeId());
                workflowNodeEngine.advanceWorkflow(
                        instance.getId(),
                        currentNodeExecution.getNodeId(),
                        currentNodeExecution.getOutputData()
                );
                recoveredCount++;
            } catch (Exception exception) {
                log.error("补推进已完成节点失败: instanceId={}, nodeId={}",
                        instance.getId(), currentNodeExecution.getNodeId(), exception);
            }
        }
        return recoveredCount;
    }

    private int recoverPendingCurrentNodes(int batchSize) {
        List<WorkflowInstance> activeInstances = workflowInstanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .in(WorkflowInstance::getStatus, RECOVERABLE_INSTANCE_STATUS)
                        .isNotNull(WorkflowInstance::getCurrentNodeId)
                        .orderByAsc(WorkflowInstance::getUpdatedAt)
                        .last("limit " + batchSize)
        );
        if (activeInstances.isEmpty()) {
            return 0;
        }

        int recoveredCount = 0;
        for (WorkflowInstance instance : activeInstances) {
            List<WorkflowNodeExecution> nodeExecutions = workflowNodeExecutionMapper.selectList(
                    new LambdaQueryWrapper<WorkflowNodeExecution>()
                            .eq(WorkflowNodeExecution::getInstanceId, instance.getId())
                            .orderByAsc(WorkflowNodeExecution::getCreatedAt)
            );
            if (nodeExecutions.isEmpty()) {
                continue;
            }
            boolean hasRunningNode = nodeExecutions.stream()
                    .anyMatch(node -> WorkflowInstanceStatusEnum.RUNNING.getCode().equals(node.getStatus()));
            if (hasRunningNode) {
                continue;
            }

            WorkflowNodeExecution currentNodeExecution = nodeExecutions.stream()
                    .filter(node -> instance.getCurrentNodeId().equals(node.getNodeId()))
                    .findFirst()
                    .orElse(null);
            if (currentNodeExecution == null
                    || !WorkflowInstanceStatusEnum.PENDING.getCode().equals(currentNodeExecution.getStatus())) {
                continue;
            }

            try {
                if (workflowNodeEngine.drivePendingCurrentNode(instance)) {
                    recoveredCount++;
                }
            } catch (Exception exception) {
                log.error("重驱动待执行节点失败: instanceId={}, nodeId={}",
                        instance.getId(), currentNodeExecution.getNodeId(), exception);
            }
        }
        return recoveredCount;
    }

    private String resolveCallbackPayload(AgentExecution agentExecution) {
        if (agentExecution == null) {
            return "Agent执行记录不存在";
        }
        if (StringUtils.hasText(agentExecution.getOutputResult())) {
            return agentExecution.getOutputResult();
        }
        if (StringUtils.hasText(agentExecution.getErrorMessage())) {
            return agentExecution.getErrorMessage();
        }
        return "Agent执行结束，但未返回输出内容";
    }
}
