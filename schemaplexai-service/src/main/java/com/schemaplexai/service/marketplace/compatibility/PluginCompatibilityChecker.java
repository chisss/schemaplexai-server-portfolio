package com.schemaplexai.service.marketplace.compatibility;

import com.schemaplexai.model.entity.PluginCatalog;

public interface PluginCompatibilityChecker {
    boolean check(PluginCatalog catalog, String tenantId);
}
