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
public class ClaudePluginAdapter implements PluginVendorAdapter {

    @Override
    public String vendor() {
        return "claude";
    }

    @Override
    public List<PluginCatalog> fetchPluginList() {
        log.debug("拉取 Claude 插件目录（模拟）");
        return List.of(
                buildPlugin("claude.git.repo.sync", "GitRepoSync", "Claude Git 仓库同步",
                        "同步代码仓库并执行基础检查", "mcp", "1.0.0"),
                buildPlugin("claude.doc.indexer", "DocIndexer", "Claude 文档索引",
                        "构建团队文档索引并支持语义检索", "skill", "1.1.0")
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
        plugin.setSourceUrl("https://api.anthropic.com/plugins/" + pluginUid);
        plugin.setIconUrl("https://static.anthropic.com/plugins/" + pluginUid + ".png");
        plugin.setSignature("mock-signature-" + pluginUid);
        plugin.setManifest(Map.of("provider", vendor(), "pluginUid", pluginUid,
                "pluginType", pluginType, "version", version));
        plugin.setStatus(CommonConstant.STATUS_ACTIVE);
        return plugin;
    }
}
