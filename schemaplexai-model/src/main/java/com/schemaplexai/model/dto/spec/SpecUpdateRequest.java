package com.schemaplexai.model.dto.spec;

import lombok.Data;

import java.util.List;

/**
 * 更新Spec请求DTO
 */
@Data
public class SpecUpdateRequest {

    /** Spec名称 */
    private String name;

    /** 分类 */
    private String category;

    /** 描述 */
    private String description;

    /** 标签 */
    private List<String> tags;

    /** 关联工作流模板ID */
    private String workflowId;
}
