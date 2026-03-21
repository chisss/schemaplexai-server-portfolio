package com.schemaplexai.model.dto.integration;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 创建集成配置请求
 */
@Data
public class IntegrationCreateRequest {

    /** 集成类型: git/cicd/pm/im */
    @NotBlank(message = "集成类型不能为空")
    private String integrationType;

    /** 平台: github/gitlab/gitee/jenkins/... */
    @NotBlank(message = "平台不能为空")
    private String platform;

    /** 名称 */
    @NotBlank(message = "名称不能为空")
    @Size(max = 100, message = "名称不能超过100个字符")
    private String name;

    /** 配置信息(OAuth/API Key等) */
    @NotNull(message = "配置不能为空")
    private Map<String, Object> config;
}
