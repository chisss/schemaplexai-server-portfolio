package com.schemaplexai.service.marketplace.signature;

import com.schemaplexai.model.entity.PluginCatalog;

public interface PluginSignatureVerifier {
    boolean verify(PluginCatalog catalog);
}
