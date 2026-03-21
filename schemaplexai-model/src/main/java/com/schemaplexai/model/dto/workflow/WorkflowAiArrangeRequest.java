package com.schemaplexai.model.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * AI工作流编排请求
 */
@Data
public class WorkflowAiArrangeRequest {

    /** 编排目标描述（用户输入） */
    @NotBlank(message = "编排描述不能为空")
    private String prompt;

    /** 关联的 Spec ID（用于提供业务上下文） */
    private String specId;
}
