package com.schemaplexai.service.marketplace;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.marketplace.MarketplacePluginQueryRequest;
import com.schemaplexai.model.vo.marketplace.MarketplacePluginVO;

/**
 * 插件市场服务
 */
public interface MarketplaceService {

    /**
     * 分页查询插件列表
     */
    PageResult<MarketplacePluginVO> listPlugins(MarketplacePluginQueryRequest request);

    /**
     * 安装插件
     */
    void installPlugin(String pluginId);

    /**
     * 卸载插件
     */
    void uninstallPlugin(String pluginId);
}
