package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.marketplace.MarketplacePluginQueryRequest;
import com.schemaplexai.model.vo.marketplace.MarketplacePluginVO;
import com.schemaplexai.service.marketplace.MarketplaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 插件市场控制器
 */
@RestController
@RequestMapping("/marketplace/plugins")
@RequiredArgsConstructor
@Tag(name = "插件市场")
public class MarketplaceController {

    private final MarketplaceService marketplaceService;

    @GetMapping
    @Operation(summary = "分页查询插件市场列表")
    public R<PageResult<MarketplacePluginVO>> list(MarketplacePluginQueryRequest request) {
        return R.ok(marketplaceService.listPlugins(request));
    }

    @PostMapping("/{id}/install")
    @Operation(summary = "安装插件")
    public R<Void> install(@PathVariable String id) {
        marketplaceService.installPlugin(id);
        return R.ok();
    }

    @DeleteMapping("/{id}/install")
    @Operation(summary = "卸载插件")
    public R<Void> uninstall(@PathVariable String id) {
        marketplaceService.uninstallPlugin(id);
        return R.ok();
    }
}
