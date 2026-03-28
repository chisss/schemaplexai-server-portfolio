package com.schemaplexai.service.tool.security;

import com.schemaplexai.common.constant.ToolConfigConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具安全校验器
 */
@Slf4j
@Component
public class ToolSecurityValidator {

    private static final Set<String> PATH_KEYS = Set.of(
            "path", "file", "dir", "directory", "workspace",
            "filepath", "filename", "root", "basedir"
    );

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "secret", "token", "api_key", "apikey",
            "private_key", "access_key", "credential", "auth",
            "cert", "key", "secret_key", "session_token"
    );

    private static final Pattern SQL_DANGEROUS_PATTERN = Pattern.compile(
            "(?i)(--|/\\*|\\*/|;|\\b(drop|truncate|alter|create|grant|revoke|union\\s+select)\\b)");

    /**
     * 校验租户隔离
     */
    public void validateTenantIsolation(String currentTenantId, String resourceTenantId) {
        if (!StringUtils.hasText(currentTenantId) || !StringUtils.hasText(resourceTenantId)) {
            return;
        }
        if (!currentTenantId.equals(resourceTenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户隔离校验失败");
        }
    }

    /**
     * 校验路径边界（防止 ../ 遍历）
     */
    public void validatePathBoundary(String basePath, String targetPath) {
        if (!StringUtils.hasText(targetPath)) {
            return;
        }
        String normalizedTarget = targetPath.trim();
        if (normalizedTarget.contains("..")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "路径包含非法遍历片段");
        }
        if (Paths.get(normalizedTarget).isAbsolute()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不允许使用绝对路径");
        }

        String safeBasePath = StringUtils.hasText(basePath) ? basePath : System.getProperty("user.dir");
        Path base = Paths.get(safeBasePath).toAbsolutePath().normalize();
        Path resolved = base.resolve(normalizedTarget).normalize();
        if (!resolved.startsWith(base)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "路径越界访问被拒绝");
        }
    }

    /**
     * 校验 SQL 安全性
     */
    public void validateSqlSafety(String sql) {
        if (!StringUtils.hasText(sql)) {
            return;
        }
        String normalizedSql = sql.trim();
        if (SQL_DANGEROUS_PATTERN.matcher(normalizedSql).find()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "SQL 包含潜在危险语句");
        }
    }

    /**
     * 校验响应不包含未脱敏敏感信息
     */
    public void validateCredentialNotExposed(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        if (containsUnmaskedSensitiveValue(payload)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "响应包含未脱敏敏感信息");
        }
    }

    /**
     * 校验运行时路径配置
     */
    public void validateRuntimePathConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        String basePath = System.getenv(ToolConfigConstant.ENV_TOOL_WORKSPACE_BASE_PATH);
        validatePathInObject(basePath, "", config);
    }

    /**
     * 脱敏敏感字段
     */
    public Map<String, Object> maskSensitiveMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return maskMap(source);
    }

    @SuppressWarnings("unchecked")
    private void validatePathInObject(String basePath, String keyPath, Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String currentPath = StringUtils.hasText(keyPath) ? keyPath + "." + key : key;
                validatePathInObject(basePath, currentPath, entry.getValue());
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            for (Object item : listValue) {
                validatePathInObject(basePath, keyPath, item);
            }
            return;
        }
        if (value instanceof String textValue && isPathKey(keyPath)) {
            validatePathBoundary(basePath, textValue);
        }
    }

    private boolean isPathKey(String keyPath) {
        if (!StringUtils.hasText(keyPath)) {
            return false;
        }
        String lowerKey = keyPath.toLowerCase(Locale.ROOT);
        for (String pathKey : PATH_KEYS) {
            if (lowerKey.endsWith(pathKey) || lowerKey.contains("_" + pathKey)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean containsUnmaskedSensitiveValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            for (Map.Entry<?, ?> entry : mapValue.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                Object item = entry.getValue();
                if (isSensitiveKey(key) && item instanceof String text
                        && StringUtils.hasText(text)
                        && !ToolConfigConstant.DEFAULT_MASK_VALUE.equals(text)) {
                    return true;
                }
                if (containsUnmaskedSensitiveValue(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> listValue) {
            for (Object item : listValue) {
                if (containsUnmaskedSensitiveValue(item)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> maskMap(Map<String, Object> source) {
        Map<String, Object> masked = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (isSensitiveKey(key)) {
                masked.put(key, ToolConfigConstant.DEFAULT_MASK_VALUE);
                continue;
            }
            if (value instanceof Map<?, ?> childMap) {
                masked.put(key, maskMap((Map<String, Object>) childMap));
            } else if (value instanceof List<?> listValue) {
                masked.put(key, maskList(listValue));
            } else {
                masked.put(key, value);
            }
        }
        return masked;
    }

    @SuppressWarnings("unchecked")
    private List<Object> maskList(List<?> source) {
        List<Object> masked = new ArrayList<>(source.size());
        for (Object item : source) {
            if (item instanceof Map<?, ?> itemMap) {
                masked.add(maskMap((Map<String, Object>) itemMap));
            } else if (item instanceof List<?> itemList) {
                masked.add(maskList(itemList));
            } else {
                masked.add(item);
            }
        }
        return masked;
    }

    private boolean isSensitiveKey(String key) {
        if (!StringUtils.hasText(key)) {
            return false;
        }
        String lowerKey = key.toLowerCase(Locale.ROOT);
        for (String sensitiveKey : SENSITIVE_KEYS) {
            if (lowerKey.contains(sensitiveKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验 URL 安全性（SSRF 防护）
     */
    public void validateUrl(String url) {
        if (!StringUtils.hasText(url)) {
            return;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "仅支持 HTTP/HTTPS 协议");
            }

            String host = uri.getHost();
            if (!StringUtils.hasText(host)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "URL 主机名为空");
            }

            // 检查是否为内网地址
            InetAddress address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) {
                throw new BusinessException(ResultCode.FORBIDDEN, "不允许访问内网地址: " + host);
            }

            // DNS Rebinding 防护：解析多个 IP
            InetAddress[] allAddresses = InetAddress.getAllByName(host);
            for (InetAddress addr : allAddresses) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()) {
                    throw new BusinessException(ResultCode.FORBIDDEN, "解析到内网地址: " + addr.getHostAddress());
                }
            }
        } catch (UnknownHostException e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无效的主机名: " + e.getMessage());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "URL 格式非法");
        }
    }
}
