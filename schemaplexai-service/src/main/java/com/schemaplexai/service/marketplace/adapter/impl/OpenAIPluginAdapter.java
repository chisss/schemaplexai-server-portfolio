package com.schemaplexai.service.marketplace.adapter.impl;

import com.schemaplexai.model.entity.PluginCatalog;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.service.marketplace.adapter.PluginVendorAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAIPluginAdapter implements PluginVendorAdapter {

    @Override
    public String vendor() {
        return "openai";
    }

    @Override
    public List<PluginCatalog> fetchPluginList() {
        log.debug("拉取 OpenAI 插件目录（模拟）");
        return List.of(
                buildPlugin("openai.browser.search", "BrowserSearch", "OpenAI 浏览器搜索",
                        "提供网页检索与页面抓取能力", "mcp", "2.0.0"),
                buildPlugin("openai.data.analyzer", "DataAnalyzer", "OpenAI 数据分析器",
                        "执行 CSV/JSON 结构化分析", "skill", "1.3.2")
        );
    }

    @Override
    public PluginCatalog fetchPluginDetail(String pluginUid) {
        if (!StringUtils.hasText(pluginUid)) {
            return null;
        }
        return fetchPluginList().stream()
                .filter(item -> pluginUid.equals(item.getPluginUid()))
                .findFirst()
                .orElse(null);
    }

    private PluginCatalog buildPlugin(String pluginUid, String name, String displayName,
                                      String description, String pluginType, String version) {
        PluginCatalog plugin = new PluginCatalog();
        plugin.setPluginUid(pluginUid);
        plugin.setName(name);
        plugin.setDisplayName(displayName);
        plugin.setDescription(description);
        plugin.setVendor(vendor());
        plugin.setPluginType(pluginType);
        plugin.setVersion(version);
        plugin.setSourceUrl("https://api.openai.com/plugins/" + pluginUid);
        plugin.setIconUrl("https://static.openai.com/plugins/" + pluginUid + ".png");
        plugin.setSignature("mock-signature-" + pluginUid);
        plugin.setManifest(Map.of("provider", vendor(), "pluginUid", pluginUid,
                "pluginType", pluginType, "version", version));
        plugin.setStatus(CommonConstant.STATUS_ACTIVE);
        return plugin;
    }
}
