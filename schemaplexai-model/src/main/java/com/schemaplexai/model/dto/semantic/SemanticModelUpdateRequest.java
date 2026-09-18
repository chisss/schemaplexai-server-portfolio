package com.schemaplexai.model.dto.semantic;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 更新语义模型请求。 */
public record SemanticModelUpdateRequest(
        @NotBlank(message = "模型名称不能为空")
        @Size(max = 200, message = "模型名称不能超过200个字符")
        String name,
        @NotBlank(message = "业务域不能为空")
        @Size(max = 100, message = "业务域不能超过100个字符")
        String domain,
        @Size(max = 2000, message = "描述不能超过2000个字符")
        String description,
        @Min(value = 0, message = "revision不能小于0")
        long expectedRevision) {
}
