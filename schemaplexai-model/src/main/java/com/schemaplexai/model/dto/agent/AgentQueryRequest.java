package com.schemaplexai.model.dto.agent;

import lombok.Data;

/**
 * Agent分页查询请求DTO
 */
@Data
public class AgentQueryRequest {

    /** 当前页码，默认1 */
    private Integer page = 1;

    /** 每页条数，默认10 */
    private Integer size = 10;

    /** 模糊搜索关键词（名称/描述） */
    private String keyword;

    /** Agent类型筛选 */
    private String agentType;

    /** 状态筛选 */
    private String status;

    /** Agent能力标签筛选（模糊匹配） */
    private String agentTag;

    /** 是否内置Agent筛选 */
    private Boolean isBuiltin;

    /**
     * 按内置位置筛选（用于上下文创建等场景）
     * 传入 builtin_position 值（如 "context-create"），返回该位置的内置Agent，忽略 status 过滤
     */
    private String builtinPosition;
}
