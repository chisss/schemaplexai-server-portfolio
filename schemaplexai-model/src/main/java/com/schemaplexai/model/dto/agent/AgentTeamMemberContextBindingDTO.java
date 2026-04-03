package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Team 成员上下文绑定请求
 */
@Data
public class AgentTeamMemberContextBindingDTO {

    private String contextId;

    @NotBlank(message = "来源类型不能为空")
    private String sourceType;

    private Map<String, Object> sourceConfig;

    private String content;

    private String title;

    private Integer sortOrder;
}
