package com.schemaplexai.model.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 创建工作流模板请求
 */
@Data
public class WorkflowTemplateCreateRequest {

    @NotBlank(message = "模板名称不能为空")
    private String name;

    private String description;

    @NotBlank(message = "模板分类不能为空")
    private String category;

    /** ReactFlow 节点+边 JSON 定义 */
    @NotNull(message = "工作流定义不能为空")
    private Map<String, Object> definition;

    /** 推荐的Agent技能列表 */
    private List<String> recommendedAgentSkills;
}
