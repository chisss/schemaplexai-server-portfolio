package com.schemaplexai.service.marketplace.security;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.PluginCatalog;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

@Slf4j
@Component
public class DefaultPluginSecurityFilter implements PluginSecurityFilter {

    private final Set<String> allowedDomains;

    public DefaultPluginSecurityFilter(
            @Value("${marketplace.security.allowed-domains:api.openai.com,api.anthropic.com,generativelanguage.googleapis.com}") String domains) {
        this.allowedDomains = new HashSet<>();
        Arrays.stream(domains.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(item -> item.toLowerCase(Locale.ROOT))
                .forEach(this.allowedDomains::add);
    }

    @Override
    public void validatePluginCatalog(PluginCatalog catalog) {
        if (catalog == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件目录为空");
        }
        validateUrl(catalog.getSourceUrl());
        if (StringUtils.hasText(catalog.getIconUrl())) {
            validateUrl(catalog.getIconUrl());
        }
    }

    @Override
    public void validateUrl(String url) {
        if (!StringUtils.hasText(url)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件来源 URL 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (Exception exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件来源 URL 非法");
        }

        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅允许 HTTPS 协议");
        }

        if (StringUtils.hasText(uri.getUserInfo())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "URL 不允许包含用户信息");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "仅允许 443 端口");
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件来源域名为空");
        }
        String normalizedHost = host.trim().toLowerCase(Locale.ROOT);

        if (!isWhitelistedDomain(normalizedHost)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件来源域名不在白名单内");
        }

        verifyHostNotPrivateAddress(normalizedHost);
    }

    private boolean isWhitelistedDomain(String host) {
        for (String allowed : allowedDomains) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private void verifyHostNotPrivateAddress(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (isPrivateOrLocalAddress(address)) {
                    log.warn("检测到潜在 SSRF 目标地址: host={}, ip={}", host, address.getHostAddress());
                    throw new BusinessException(ResultCode.BAD_REQUEST, "插件来源地址存在安全风险");
                }
            }
        } catch (UnknownHostException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "插件来源域名解析失败");
        }
    }

    private boolean isPrivateOrLocalAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        String ip = address.getHostAddress();
        return isPrivateIpv4(ip) || isLocalIpv6(ip);
    }

    private boolean isPrivateIpv4(String ip) {
        if (!StringUtils.hasText(ip) || !ip.contains(".")) {
            return false;
        }
        if (ip.startsWith("10.") || ip.startsWith("127.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
            return true;
        }
        if (ip.startsWith("172.")) {
            String[] segments = ip.split("\\.");
            if (segments.length > 1) {
                try {
                    int second = Integer.parseInt(segments[1]);
                    return second >= 16 && second <= 31;
                } catch (NumberFormatException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isLocalIpv6(String ip) {
        if (!StringUtils.hasText(ip) || !ip.contains(":")) {
            return false;
        }
        String normalized = ip.toLowerCase(Locale.ROOT);
        return "::1".equals(normalized) || normalized.startsWith("fe80:")
                || normalized.startsWith("fc") || normalized.startsWith("fd");
    }
}
