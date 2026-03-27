package com.schemaplexai.service.marketplace.signature;

import com.schemaplexai.model.entity.PluginCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

@Slf4j
@Component
public class DefaultPluginSignatureVerifier implements PluginSignatureVerifier {

    private static final String SHA256_PREFIX = "sha256:";
    private static final String MOCK_PREFIX = "mock-signature-";

    @Value("${marketplace.signature.allow-mock:false}")
    private boolean allowMockSignature;

    @Override
    public boolean verify(PluginCatalog catalog) {
        if (catalog == null || !StringUtils.hasText(catalog.getSignature())) {
            return false;
        }
        String signature = catalog.getSignature().trim();

        if (signature.toLowerCase(Locale.ROOT).startsWith(SHA256_PREFIX)) {
            String expected = SHA256_PREFIX + sha256Hex(buildCanonicalPayload(catalog));
            return expected.equalsIgnoreCase(signature);
        }

        if (signature.startsWith(MOCK_PREFIX)) {
            if (!allowMockSignature) {
                log.warn("检测到 mock 签名但系统未允许: pluginUid={}", catalog.getPluginUid());
                return false;
            }
            String suffix = signature.substring(MOCK_PREFIX.length());
            return StringUtils.hasText(catalog.getPluginUid()) && suffix.equals(catalog.getPluginUid());
        }

        log.warn("不支持的签名算法或格式: pluginUid={}, signature={}", catalog.getPluginUid(), signature);
        return false;
    }

    private String buildCanonicalPayload(PluginCatalog catalog) {
        String pluginUid = safeText(catalog.getPluginUid());
        String vendor = safeText(catalog.getVendor());
        String pluginType = safeText(catalog.getPluginType());
        String version = safeText(catalog.getVersion());
        String sourceUrl = safeText(catalog.getSourceUrl());
        return pluginUid + "|" + vendor + "|" + pluginType + "|" + version + "|" + sourceUrl;
    }

    private String safeText(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                String hex = Integer.toHexString(value & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (Exception exception) {
            log.error("计算 SHA-256 失败: {}", exception.getMessage(), exception);
            return "";
        }
    }
}
