package com.schemaplexai.model.dto.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Spec文档请求DTO
 */
@Data
public class SpecDocumentRequest {

    /** 文档内容（Markdown） */
    @NotBlank(message = "文档内容不能为空")
    private String content;

    /** 变更说明 */
    private String changeSummary;
}
