package com.schemaplexai.model.dto.integration;

import lombok.Data;

/**
 * 集成配置查询请求
 */
@Data
public class IntegrationQueryRequest {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页大小 */
    private Integer size = 20;

    /** 集成类型 */
    private String integrationType;

    /** 平台 */
    private String platform;

    /** 状态 */
    private String status;

    /** 关键字搜索 */
    private String keyword;
}
