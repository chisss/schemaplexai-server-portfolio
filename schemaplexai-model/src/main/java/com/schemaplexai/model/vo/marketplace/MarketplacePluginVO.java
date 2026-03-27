package com.schemaplexai.model.vo.marketplace;

import lombok.Data;

/**
 * 插件市场条目 VO
 */
@Data
public class MarketplacePluginVO {

    private String id;
    private String name;
    private String description;
    private String category;
    private String sourceType;
    private String status;
    private Boolean installed;
    private String installedVersion;
}
