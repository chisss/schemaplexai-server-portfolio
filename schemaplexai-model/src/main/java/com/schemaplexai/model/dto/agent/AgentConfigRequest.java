package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Agent配置请求DTO
 */
@Data
public class AgentConfigRequest {

    /** 配置键 */
    @NotBlank(message = "配置键不能为空")
    private String configKey;

    /** 配置值 */
    @NotBlank(message = "配置值不能为空")
    private String configValue;

    /** 说明 */
    private String description;
}
