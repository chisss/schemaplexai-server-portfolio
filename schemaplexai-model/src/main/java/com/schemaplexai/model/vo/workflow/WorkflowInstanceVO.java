package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流实例VO
 */
@Data
public class WorkflowInstanceVO {

    private String id;

    private String templateId;

    private String specId;

    private String name;

    private String status;

    private String currentNodeId;

    private Map<String, Object> variables;

    private Map<String, Object> definition;

    /** 节点执行记录列表 */
    private List<WorkflowNodeExecutionVO> nodeExecutions;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String createdBy;

    private LocalDateTime createdAt;
}
