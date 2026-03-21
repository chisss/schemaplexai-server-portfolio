package com.schemaplexai.model.dto.spec;

import lombok.Data;

/**
 * Spec模板查询请求
 */
@Data
public class SpecTemplateQueryRequest {

    private Integer page = 1;
    private Integer size = 20;

    /** 分类筛选 */
    private String category;

    /** 文档类型筛选 */
    private String docType;

    /** 关键词搜索 */
    private String keyword;
}
