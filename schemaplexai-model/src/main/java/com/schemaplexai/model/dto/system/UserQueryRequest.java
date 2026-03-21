package com.schemaplexai.model.dto.system;

import lombok.Data;

/**
 * 用户分页查询请求DTO
 */
@Data
public class UserQueryRequest {

    /** 当前页码，默认1 */
    private Integer page = 1;

    /** 每页条数，默认10 */
    private Integer size = 10;

    /** 模糊搜索关键词（用户名/姓名） */
    private String keyword;

    /** 状态筛选 */
    private String status;
}
