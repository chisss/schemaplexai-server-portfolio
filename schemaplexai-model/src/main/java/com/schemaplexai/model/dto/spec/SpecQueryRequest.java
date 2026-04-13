package com.schemaplexai.model.dto.spec;

import lombok.Data;

/**
 * Spec分页查询请求DTO
 */
@Data
public class SpecQueryRequest {

    /** 当前页码，默认1 */
    private Integer page = 1;

    /** 每页条数，默认10 */
    private Integer size = 10;

    /** 模糊搜索关键词（名称/描述） */
    private String keyword;

    /** 状态筛选 */
    private String status;

    /** 分类筛选 */
    private String category;

    /** 需求类型筛选 */
    private String specType;

    /** 优先级筛选 */
    private String priority;

    /** 拥有者筛选 */
    private String owner;

    /** 项目ID筛选 */
    private String projectId;
}
