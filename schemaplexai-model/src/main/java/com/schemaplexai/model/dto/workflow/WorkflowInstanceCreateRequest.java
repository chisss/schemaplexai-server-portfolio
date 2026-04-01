package com.schemaplexai.model.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 创建工作流实例请求
 */
@Data
public class WorkflowInstanceCreateRequest {

    @NotBlank(message = "模板ID不能为空")
    private String templateId;

    /** 租户ID */
    private String tenantId;

    /** 关联的Spec ID */
    private String specId;

    @NotBlank(message = "实例名称不能为空")
    private String name;

    /** 流程变量 */
    private Map<String, Object> variables;
}
