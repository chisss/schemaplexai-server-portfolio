package com.schemaplexai.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 初始化 Agent 专属指令请求
 */
@Data
public class AgentInitInstructionsDTO {

    /**
     * 模板类型：claude / openai / general
     */
    @NotBlank(message = "模板类型不能为空")
    @Pattern(regexp = "^(claude|openai|general)$", message = "模板类型不合法")
    private String template;

    /**
     * 自定义指令标题（可选，默认使用模板名）
     */
    @Size(max = 255, message = "标题长度不能超过255个字符")
    private String title;
}
