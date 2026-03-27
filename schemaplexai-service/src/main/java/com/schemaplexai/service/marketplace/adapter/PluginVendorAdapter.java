package com.schemaplexai.service.marketplace.adapter;

import com.schemaplexai.model.entity.PluginCatalog;

import java.util.List;

public interface PluginVendorAdapter {

    String vendor();

    List<PluginCatalog> fetchPluginList();

    PluginCatalog fetchPluginDetail(String pluginUid);
}
