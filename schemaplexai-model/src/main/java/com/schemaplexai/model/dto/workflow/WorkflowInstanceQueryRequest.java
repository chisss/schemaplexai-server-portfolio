package com.schemaplexai.model.dto.workflow;

import lombok.Data;

/**
 * 工作流实例查询请求
 */
@Data
public class WorkflowInstanceQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String status;

    private String specId;
}
