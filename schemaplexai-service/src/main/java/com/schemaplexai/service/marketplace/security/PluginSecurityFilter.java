package com.schemaplexai.service.marketplace.security;

import com.schemaplexai.model.entity.PluginCatalog;

public interface PluginSecurityFilter {
    void validatePluginCatalog(PluginCatalog catalog);
    void validateUrl(String url);
}
