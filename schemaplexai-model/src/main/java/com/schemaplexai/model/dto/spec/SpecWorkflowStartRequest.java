package com.schemaplexai.model.dto.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 启动 Spec 工作流请求
 */
@Data
public class SpecWorkflowStartRequest {

    /**
     * 原始需求描述
     */
    @NotBlank(message = "原始需求不能为空")
    private String description;
}
