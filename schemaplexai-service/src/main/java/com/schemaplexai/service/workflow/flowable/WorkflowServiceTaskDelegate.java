package com.schemaplexai.service.workflow.flowable;

import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ServiceTask 统一 Delegate
 *
 * <p>所有映射为 ServiceTask 的节点（notification, deviation_analysis, quality_report, script 等）
 * 在 Flowable 引擎流转到该 task 时会同步调用此 delegate。
 * <p>此 delegate 桥接到 WorkflowNodeEngine 的同步执行方法，完成后 Flowable 自动流转到下一节点。
 */
@Slf4j
@Component("workflowServiceTaskDelegate")
@RequiredArgsConstructor
public class WorkflowServiceTaskDelegate implements JavaDelegate {

    private final WorkflowNodeEngine workflowNodeEngine;
    private final WorkflowInstanceMapper instanceMapper;

    @Override
    public void execute(DelegateExecution execution) {
        String nodeId = FlowableExtensionUtil.getExtensionValue(execution, "nodeId");
        String nodeType = FlowableExtensionUtil.getExtensionValue(execution, "nodeType");
        String sfInstanceId = (String) execution.getVariable("sfInstanceId");

        log.info("ServiceTask delegate 执行: nodeId={}, nodeType={}, sfInstanceId={}, executionId={}",
                nodeId, nodeType, sfInstanceId, execution.getId());

        WorkflowInstance instance = instanceMapper.selectById(sfInstanceId);
        if (instance == null) {
            log.error("工作流实例不存在: sfInstanceId={}", sfInstanceId);
            return;
        }

        // 获取上一节点的输出作为本节点的输入
        Map<String, Object> previousOutput = resolvePreviousOutput(instance, nodeId);

        // 同步执行节点逻辑（由 WorkflowNodeEngine 分发到具体 handler）
        workflowNodeEngine.executeNodeSync(instance, nodeId, previousOutput);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resolvePreviousOutput(WorkflowInstance instance, String currentNodeId) {
        if (instance.getVariables() == null) {
            return new HashMap<>();
        }
        // 从 definition 的 edges 中找到上游节点
        Map<String, Object> definition = instance.getDefinition();
        if (definition == null) {
            return new HashMap<>();
        }
        Object rawEdges = definition.get("edges");
        if (!(rawEdges instanceof List<?> edges)) {
            return new HashMap<>();
        }
        // 找到指向当前节点的边的源节点
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
