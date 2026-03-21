package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Agent 执行请求 DTO
 */
@Data
public class AgentExecuteDTO {

    /** 执行任务描述/指令 */
    @NotBlank(message = "执行指令不能为空")
    private String prompt;

    /** 附加上下文数据 */
    private Map<String, Object> context;

    /** 指定执行模型（为空时使用 Agent 默认模型） */
    private String model;

    /** 是否流式输出 */
    private Boolean stream = false;
}
