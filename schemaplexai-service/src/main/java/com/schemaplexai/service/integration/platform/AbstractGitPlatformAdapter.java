package com.schemaplexai.service.integration.platform;

import com.schemaplexai.common.enums.GitPlatformEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Git 平台适配器抽象基类
 * <p>
 * 提供 API URL 解析和 Token 提取等公共方法。
 * </p>
 */
public abstract class AbstractGitPlatformAdapter implements GitPlatformAdapter {

    /** 解析 API URL，优先使用 config 中的 apiUrl，否则使用平台默认值 */
    protected String resolveApiUrl(Map<String, Object> config, GitPlatformEnum platform) {
        Object apiUrl = config.get("apiUrl");
        if (apiUrl != null && StringUtils.hasText(apiUrl.toString())) {
            return apiUrl.toString().replaceAll("/+$", "");
        }
        if (StringUtils.hasText(platform.getDefaultApiUrl())) {
            return platform.getDefaultApiUrl();
        }
        throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID, "缺少 apiUrl 配置");
    }

    /** 从 config 中提取 accessToken，缺失则抛异常 */
    protected String requireToken(Map<String, Object> config) {
        Object token = config.get("accessToken");
        if (token == null || !StringUtils.hasText(token.toString())) {
            throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID, "缺少 accessToken");
        }
        return token.toString();
    }
}
