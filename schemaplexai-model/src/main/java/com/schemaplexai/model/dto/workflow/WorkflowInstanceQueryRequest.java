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

    /** 触发类型筛选（匹配 variables->>'triggerType'） */
    private String triggerType;

    /** 关键词搜索（模糊匹配实例名称） */
    private String keyword;
}
