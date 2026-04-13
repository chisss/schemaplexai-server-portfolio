package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 工作流节点执行记录VO
 */
@Data
public class WorkflowNodeExecutionVO {

    private String id;

    private String nodeId;

    private String nodeType;

    private String nodeLabel;

    private String status;

    private Map<String, Object> inputData;

    private Map<String, Object> outputData;

    private String errorMessage;

    private String reviewSessionId;

    private String actionUrl;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
