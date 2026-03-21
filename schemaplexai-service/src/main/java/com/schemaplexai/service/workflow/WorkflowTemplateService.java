package com.schemaplexai.service.workflow;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.workflow.WorkflowAiArrangeRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateQueryRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateUpdateRequest;
import com.schemaplexai.model.vo.workflow.WorkflowAiArrangeVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateVO;

/**
 * 工作流模板服务接口
 */
public interface WorkflowTemplateService {

    WorkflowTemplateVO create(WorkflowTemplateCreateRequest request);

    WorkflowTemplateVO update(String id, WorkflowTemplateUpdateRequest request);

    void delete(String id);

    WorkflowTemplateVO getById(String id);

    PageResult<WorkflowTemplateVO> page(WorkflowTemplateQueryRequest query);

    /** AI自动编排工作流节点 */
    WorkflowAiArrangeVO aiArrange(String templateId, WorkflowAiArrangeRequest request);
}
