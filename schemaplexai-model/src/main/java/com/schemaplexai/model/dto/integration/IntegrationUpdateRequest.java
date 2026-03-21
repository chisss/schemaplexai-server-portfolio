package com.schemaplexai.model.dto.integration;

import lombok.Data;

import java.util.Map;

/**
 * 更新集成配置请求
 */
@Data
public class IntegrationUpdateRequest {

    /** 名称 */
    private String name;

    /** 配置信息 */
    private Map<String, Object> config;

    /** 状态: active/inactive */
    private String status;
}
