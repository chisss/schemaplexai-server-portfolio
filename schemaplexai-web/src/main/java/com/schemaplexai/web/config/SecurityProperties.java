package com.schemaplexai.web.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台安全配置属性
 */
@Data
@Component
@ConfigurationProperties(prefix = "schemaplexai.security")
public class SecurityProperties {

    /** 允许携带凭证访问的前端 Origin 白名单 */
    private List<String> allowedOrigins = new ArrayList<>(List.of(
            "http://127.0.0.1:5173",
            "http://localhost:5173"
    ));

    /** 开发环境是否允许默认密钥 */
    private boolean allowDefaultSecretsInDev = true;

    /** 可匿名访问的 actuator 端点 */
    private List<String> actuatorPublicEndpoints = new ArrayList<>(List.of("/actuator/health"));

    /** 数据加密密钥，由配置绑定后供启动校验使用 */
    private String aesKey;
}
