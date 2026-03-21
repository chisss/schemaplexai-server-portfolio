package com.schemaplexai.model.dto.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建Spec模板请求
 */
@Data
public class SpecTemplateCreateRequest {

    @NotBlank(message = "模板名称不能为空")
    private String name;

    /** 模板分类 */
    private String category;

    /** 文档类型: requirements/design/tasks */
    @NotBlank(message = "文档类型不能为空")
    private String docType;

    /** 模板内容（Markdown） */
    @NotBlank(message = "模板内容不能为空")
    private String content;

    /** 描述 */
    private String description;
}
