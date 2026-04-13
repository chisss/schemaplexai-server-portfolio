package com.schemaplexai.service.workflow;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.workflow.WorkflowAiArrangeRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateQueryRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateUpdateRequest;
import com.schemaplexai.model.vo.workflow.WorkflowAiArrangeVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateStatsVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateVO;

import java.util.List;

/**
 * 工作流模板服务接口
 */
public interface WorkflowTemplateService {

    WorkflowTemplateVO create(WorkflowTemplateCreateRequest request);

    WorkflowTemplateVO update(String id, WorkflowTemplateUpdateRequest request);

    void delete(String id);

    WorkflowTemplateVO getById(String id);

    PageResult<WorkflowTemplateVO> page(WorkflowTemplateQueryRequest query);

    List<WorkflowTemplateVO> listAll();

    /** AI自动编排工作流节点 */
    WorkflowAiArrangeVO aiArrange(String templateId, WorkflowAiArrangeRequest request);

    /** 获取模板统计数据 */
    WorkflowTemplateStatsVO getStats();

    /** 切换模板启用/停用状态 */
    WorkflowTemplateVO toggleStatus(String id);
}
