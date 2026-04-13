package com.schemaplexai.model.dto.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 工作台文档提交请求
 */
@Data
public class SpecDocumentSubmitRequest {

    /** 文档内容 */
    @NotBlank(message = "文档内容不能为空")
    private String content;

    /** 变更说明 */
    private String changeSummary;

    /** 提交备注 */
    private String comment;
}
