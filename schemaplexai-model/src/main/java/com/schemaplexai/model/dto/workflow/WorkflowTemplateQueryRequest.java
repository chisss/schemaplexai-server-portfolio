package com.schemaplexai.model.dto.workflow;

import lombok.Data;

/**
 * 工作流模板查询请求
 */
@Data
public class WorkflowTemplateQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String keyword;

    private String category;

    private Boolean isBuiltin;
}
