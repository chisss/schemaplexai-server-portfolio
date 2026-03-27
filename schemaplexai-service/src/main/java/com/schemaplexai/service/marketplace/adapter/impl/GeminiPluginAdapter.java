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
public class GeminiPluginAdapter implements PluginVendorAdapter {

    @Override
    public String vendor() {
        return "gemini";
    }

    @Override
    public List<PluginCatalog> fetchPluginList() {
        log.debug("拉取 Gemini 插件目录（模拟）");
        return List.of(
                buildPlugin("gemini.sheet.connector", "SheetConnector", "Gemini 表格连接器",
                        "连接并读取在线表格数据", "mcp", "1.2.0"),
                buildPlugin("gemini.workflow.builder", "WorkflowBuilder", "Gemini 工作流构建器",
                        "辅助构建自动化流程模板", "skill", "0.9.5")
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
        plugin.setSourceUrl("https://generativelanguage.googleapis.com/plugins/" + pluginUid);
        plugin.setIconUrl("https://static.google.com/plugins/" + pluginUid + ".png");
        plugin.setSignature("mock-signature-" + pluginUid);
        plugin.setManifest(Map.of("provider", vendor(), "pluginUid", pluginUid,
                "pluginType", pluginType, "version", version));
        plugin.setStatus(CommonConstant.STATUS_ACTIVE);
        return plugin;
    }
}
