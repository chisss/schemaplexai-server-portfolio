package com.schemaplexai.model.dto.database;

import lombok.Data;

/**
 * 数据库数据源查询请求
 */
@Data
public class DatabaseSourceQueryRequest {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页大小 */
    private Integer size = 20;

    /** 关键字 */
    private String keyword;

    /** 状态 */
    private String status;

    /** 数据库类型 */
    private String databaseType;

    /** 接入模式: preset / custom */
    private String connectionMode;
}
