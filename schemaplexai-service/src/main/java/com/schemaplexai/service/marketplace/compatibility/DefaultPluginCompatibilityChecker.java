package com.schemaplexai.service.marketplace.compatibility;

import com.schemaplexai.model.entity.PluginCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class DefaultPluginCompatibilityChecker implements PluginCompatibilityChecker {

    @Value("${marketplace.platform.version:1.0.0}")
    private String platformVersion;

    @Value("${marketplace.runtime.environments:prod}")
    private String runtimeEnvironments;

    @Override
    @SuppressWarnings("unchecked")
    public boolean check(PluginCatalog catalog, String tenantId) {
        if (catalog == null || !StringUtils.hasText(catalog.getPluginType())) {
            return false;
        }
        String pluginType = catalog.getPluginType().trim().toLowerCase();
        if (!"skill".equals(pluginType) && !"mcp".equals(pluginType)) {
            log.warn("插件类型不兼容: tenantId={}, pluginUid={}, pluginType={}",
                    tenantId, catalog.getPluginUid(), catalog.getPluginType());
            return false;
        }

        Map<String, Object> manifest = catalog.getManifest();
        if (CollectionUtils.isEmpty(manifest)) {
            return true;
        }

        Object minVersionObj = manifest.get("minPlatformVersion");
        if (minVersionObj != null && StringUtils.hasText(String.valueOf(minVersionObj))) {
            String minVersion = String.valueOf(minVersionObj).trim();
            if (compareVersion(platformVersion, minVersion) < 0) {
                log.warn("平台版本不兼容: tenantId={}, pluginUid={}, platformVersion={}, minRequired={}",
                        tenantId, catalog.getPluginUid(), platformVersion, minVersion);
                return false;
            }
        }

        Object envObj = manifest.get("requiredEnvironments");
        if (envObj instanceof List<?> requiredEnvList && !requiredEnvList.isEmpty()) {
            Set<String> runtimeEnvSet = parseRuntimeEnvironments(runtimeEnvironments);
            Set<String> requiredEnvSet = new HashSet<>();
            for (Object item : requiredEnvList) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    requiredEnvSet.add(String.valueOf(item).trim().toLowerCase());
                }
            }
            if (!runtimeEnvSet.containsAll(requiredEnvSet)) {
                log.warn("运行环境不兼容: tenantId={}, pluginUid={}, runtime={}, required={}",
                        tenantId, catalog.getPluginUid(), runtimeEnvSet, requiredEnvSet);
                return false;
            }
        }

        return true;
    }

    private Set<String> parseRuntimeEnvironments(String environments) {
        Set<String> result = new HashSet<>();
        if (!StringUtils.hasText(environments)) {
            return result;
        }
        Arrays.stream(environments.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .forEach(result::add);
        return result;
    }

    private int compareVersion(String v1, String v2) {
        String[] parts1 = safeVersion(v1).split("\\.");
        String[] parts2 = safeVersion(v2).split("\\.");
        int length = Math.max(parts1.length, parts2.length);
        for (int index = 0; index < length; index++) {
            int n1 = index < parts1.length ? parseNumber(parts1[index]) : 0;
            int n2 = index < parts2.length ? parseNumber(parts2[index]) : 0;
            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }
        return 0;
    }

    private String safeVersion(String value) {
        return StringUtils.hasText(value) ? value.trim() : "0";
    }

    private int parseNumber(String text) {
        try {
            return Integer.parseInt(text.replaceAll("[^0-9]", ""));
        } catch (Exception ignored) {
            return 0;
        }
    }
}
