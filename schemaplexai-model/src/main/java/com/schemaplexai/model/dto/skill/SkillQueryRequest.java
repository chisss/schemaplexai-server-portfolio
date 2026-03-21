package com.schemaplexai.model.dto.skill;

import lombok.Data;

/**
 * 技能查询请求
 */
@Data
public class SkillQueryRequest {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页大小 */
    private Integer size = 20;

    /** 分类: builtin/custom/mcp */
    private String category;

    /** 状态: active/inactive/deprecated */
    private String status;

    /** 关键字搜索（名称/显示名称/描述） */
    private String keyword;
}
