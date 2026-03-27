package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Agent 工具绑定 DTO
 */
@Data
public class AgentToolBindingDTO {

    @NotBlank(message = "工具编码不能为空")
    private String toolCode;

    @NotBlank(message = "来源类型不能为空")
    private String sourceType;

    private String sourceRefId;

    private Boolean enabled;

    private Integer priority;

    private Map<String, Object> configOverride;
}
