package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Human-in-Loop 输入 DTO
 */
@Data
public class AgentExecutionInputDTO {

    /** 用户输入文本 */
    @NotBlank(message = "输入内容不能为空")
    private String message;

    /** 扩展参数（可选） */
    private Map<String, Object> options;
}
