package com.schemaplexai.model.dto.system;

import lombok.Data;

/**
 * 角色分页查询请求DTO
 */
@Data
public class RoleQueryRequest {

    /** 当前页码，默认1 */
    private Integer page = 1;

    /** 每页条数，默认10 */
    private Integer size = 10;

    /** 模糊搜索关键词（角色名称/编码） */
    private String keyword;

    /** 状态筛选 */
    private String status;
}
