package com.schemaplexai.service.workflow.flowable;

import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;

/**
 * EndEvent ExecutionListener（end 事件）
 *
 * <p>当 Flowable 流转到 EndEvent 时触发，调用 WorkflowNodeEngine 执行结束节点逻辑。
 */
@Slf4j
@Component("workflowEndEventListener")
@RequiredArgsConstructor
public class WorkflowEndEventListener implements ExecutionListener {

    private final WorkflowNodeEngine workflowNodeEngine;
    private final WorkflowInstanceMapper instanceMapper;

    @Override
    public void notify(DelegateExecution execution) {
        String sfInstanceId = (String) execution.getVariable("sfInstanceId");
        String nodeId = resolveNodeId(execution);

        log.info("EndEvent listener 触发: sfInstanceId={}, executionId={}, nodeId={}",
                sfInstanceId, execution.getId(), nodeId);

        WorkflowInstance instance = instanceMapper.selectById(sfInstanceId);
        if (instance == null) {
            log.error("工作流实例不存在: sfInstanceId={}", sfInstanceId);
            return;
        }

        // 使用当前 Flowable EndEvent 的真实节点 ID，避免 end1/end2 等自定义节点无法回填状态。
        workflowNodeEngine.executeNodeSync(instance, nodeId, new HashMap<>());
    }

    private String resolveNodeId(DelegateExecution execution) {
        if (execution == null) {
            return "end";
        }
        if (StringUtils.hasText(execution.getCurrentActivityId())) {
            return execution.getCurrentActivityId();
        }
        if (execution.getCurrentFlowElement() != null && StringUtils.hasText(execution.getCurrentFlowElement().getId())) {
            return execution.getCurrentFlowElement().getId();
        }
        return "end";
    }
}
