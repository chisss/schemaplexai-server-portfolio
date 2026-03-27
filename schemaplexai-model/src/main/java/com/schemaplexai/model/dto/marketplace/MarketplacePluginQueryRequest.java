package com.schemaplexai.model.dto.marketplace;

import lombok.Data;

/**
 * 插件市场查询请求
 */
@Data
public class MarketplacePluginQueryRequest {

    private Integer page = 1;
    private Integer size = 20;
    private String keyword;
    private String sourceType;
    private Boolean installed;
}
