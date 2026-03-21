package com.schemaplexai.model.dto.spec;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 从模板创建Spec请求
 */
@Data
public class SpecFromTemplateRequest {

    @NotBlank(message = "Spec名称不能为空")
    private String name;

    /** 分类 */
    private String category;

    /** 描述 */
    private String description;

    /** 标签 */
    private List<String> tags;

    /** 关联项目ID（废弃，保留兼容性） */
    @Deprecated
    private String projectId;

    /** 关联工作空间ID列表 */
    private List<String> workspaceIds = new ArrayList<>();
}
