package com.schemaplexai.model.vo.workflow;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流模板VO
 */
@Data
public class WorkflowTemplateVO {

    private String id;

    private String name;

    private String description;

    private String category;

    private Map<String, Object> definition;

    private List<String> recommendedAgentSkills;

    private Boolean isBuiltin;

    private String status;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
