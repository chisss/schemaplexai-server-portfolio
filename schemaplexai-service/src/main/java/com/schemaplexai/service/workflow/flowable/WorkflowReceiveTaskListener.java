package com.schemaplexai.service.workflow.flowable;

import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReceiveTask ExecutionListener（start 事件）
 *
 * <p>当 Flowable 流转到 ReceiveTask（agent/document 节点）时触发。
 * 在事务提交后异步执行节点逻辑，确保节点执行记录已持久化。
 */
@Slf4j
@Component("workflowReceiveTaskListener")
@RequiredArgsConstructor
public class WorkflowReceiveTaskListener implements ExecutionListener {

    private final WorkflowNodeEngine workflowNodeEngine;
    private final WorkflowInstanceMapper instanceMapper;

    @Override
    public void notify(DelegateExecution execution) {
        String nodeId = FlowableExtensionUtil.getExtensionValue(execution, "nodeId");
        String nodeType = FlowableExtensionUtil.getExtensionValue(execution, "nodeType");
        String sfInstanceId = (String) execution.getVariable("sfInstanceId");

        log.info("ReceiveTask listener 触发: nodeId={}, nodeType={}, sfInstanceId={}, executionId={}",
                nodeId, nodeType, sfInstanceId, execution.getId());

        // 在事务提交后异步执行，确保 initNodeExecutionRecords 的 INSERT 已持久化
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executeNodeAfterCommit(sfInstanceId, nodeId);
                }
            });
        } else {
            executeNodeAfterCommit(sfInstanceId, nodeId);
        }
    }

    private void executeNodeAfterCommit(String sfInstanceId, String nodeId) {
        WorkflowInstance instance = instanceMapper.selectById(sfInstanceId);
        if (instance == null) {
            log.error("工作流实例不存在: sfInstanceId={}", sfInstanceId);
            return;
        }
        Map<String, Object> previousOutput = resolvePreviousOutput(instance, nodeId);
        workflowNodeEngine.executeNodeAsync(instance, nodeId, previousOutput);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvePreviousOutput(WorkflowInstance instance, String currentNodeId) {
        if (instance.getVariables() == null || instance.getDefinition() == null) {
            return new HashMap<>();
        }
        Object rawEdges = instance.getDefinition().get("edges");
        if (!(rawEdges instanceof List<?> edges)) {
            return new HashMap<>();
        }
        for (Object e : edges) {
            if (e instanceof Map<?, ?> edge) {
                if (currentNodeId.equals(String.valueOf(edge.get("target")))) {
                    String sourceNodeId = String.valueOf(edge.get("source"));
                    Object output = instance.getVariables().get(sourceNodeId + "_output");
                    if (output instanceof Map) {
                        return new HashMap<>((Map<String, Object>) output);
                    }
                }
            }
        }
        return new HashMap<>();
    }
}
