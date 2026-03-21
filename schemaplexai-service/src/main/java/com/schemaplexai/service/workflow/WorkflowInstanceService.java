package com.schemaplexai.service.workflow;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceQueryRequest;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.model.vo.workflow.WorkflowNodeExecutionVO;

import java.util.List;

/**
 * 工作流实例服务接口
 */
public interface WorkflowInstanceService {

    WorkflowInstanceVO create(WorkflowInstanceCreateRequest request);

    WorkflowInstanceVO start(String id);

    WorkflowInstanceVO pause(String id);

    WorkflowInstanceVO resume(String id);

    void terminate(String id);

    WorkflowInstanceVO getById(String id);

    PageResult<WorkflowInstanceVO> page(WorkflowInstanceQueryRequest query);

    List<WorkflowNodeExecutionVO> getNodeExecutions(String instanceId);

    void approveNode(String instanceId, String nodeId, String comment);

    void rejectNode(String instanceId, String nodeId, String comment, String rollbackToNodeId);

    void requestModify(String instanceId, String nodeId, String modifyInstruction);
}
