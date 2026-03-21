package com.schemaplexai.service.integration.validator;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 集成配置校验器 — 封装平台匹配与配置字段校验规则
 */
@Component
@RequiredArgsConstructor
public class IntegrationValidator {

    /**
     * 集成类型 -> 合法平台集合映射
     */
    private static final Map<String, Set<String>> VALID_PLATFORM_MAP = Map.of(
            "git", Set.of("github", "gitlab", "gitee", "bitbucket"),
            "cicd", Set.of("jenkins", "github_actions", "gitlab_ci"),
            "pm", Set.of("jira", "tapd", "zentao"),
            "im", Set.of("dingtalk", "feishu", "wecom", "slack")
    );

    /**
     * 校验平台与集成类型的匹配关系
     *
     * @param integrationType 集成类型（git/cicd/pm/im）
     * @param platform        平台标识
     */
    public void validatePlatformMatch(String integrationType, String platform) {
        Set<String> validPlatforms = VALID_PLATFORM_MAP.get(integrationType);
        if (validPlatforms == null || !validPlatforms.contains(platform)) {
            throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID);
        }
    }

    /**
     * 校验配置字段基础合法性：config必须包含 apiUrl 或 accessToken
     *
     * @param platform 平台标识
     * @param config   配置信息
     */
    public void validateConfigFields(String platform, Map<String, Object> config) {
        if (config == null) {
            throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID);
        }
        boolean hasApiUrl = config.containsKey("apiUrl");
        boolean hasAccessToken = config.containsKey("accessToken");
        if (!hasApiUrl && !hasAccessToken) {
            throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID);
        }
    }
}
