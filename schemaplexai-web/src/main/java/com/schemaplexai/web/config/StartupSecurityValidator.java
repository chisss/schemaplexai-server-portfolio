package com.schemaplexai.web.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * 生产环境启动安全校验
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupSecurityValidator implements ApplicationRunner {

    private static final Set<String> DEFAULT_SECRET_VALUES = Set.of(
            "default-secret-key-at-least-32-characters",
            "schemaplexai-default-jwt-secret-key-32chars",
            "schemaplexai123",
            "redis123",
            "clickhouse123",
            "minioadmin"
    );

    private final SecurityProperties securityProperties;
    private final Environment environment;

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    public void validate() {
        if (!isProdProfile()) {
            if (securityProperties.isAllowDefaultSecretsInDev()) {
                log.info("开发环境允许默认密钥，已跳过生产安全强校验");
            }
            return;
        }

        List<String> invalidKeys = new ArrayList<>();
        validateSecret("jwt.secret", invalidKeys);
        validateSecret("schemaplexai.security.aes-key", invalidKeys);
        validateSecret("spring.datasource.password", invalidKeys);
        validateSecret("spring.data.redis.password", invalidKeys);
        validateSecret("spring.rabbitmq.password", invalidKeys);
        validateSecret("minio.secret-key", invalidKeys);
        validateSecret("clickhouse.password", invalidKeys);

        if (!invalidKeys.isEmpty()) {
            throw new IllegalStateException("生产环境安全配置不完整，请设置非默认密钥: " + String.join(", ", invalidKeys));
        }
    }

    private boolean isProdProfile() {
        return Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equalsIgnoreCase);
    }

    private void validateSecret(String propertyName, List<String> invalidKeys) {
        String value = environment.getProperty(propertyName);
        if (!StringUtils.hasText(value) || DEFAULT_SECRET_VALUES.contains(value.trim())) {
            invalidKeys.add(propertyName);
        }
    }
}
