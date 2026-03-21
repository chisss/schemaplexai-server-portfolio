package com.schemaplexai.model.dto.workflow;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 更新工作流模板请求
 */
@Data
public class WorkflowTemplateUpdateRequest {

    private String name;

    private String description;

    private String category;

    private Map<String, Object> definition;

    private List<String> recommendedAgentSkills;
}
