package com.schemaplexai.web.security;

import com.schemaplexai.web.config.SecurityProperties;
import com.schemaplexai.web.config.StartupSecurityValidator;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartupSecurityValidatorTest {

    @Test
    void shouldRejectDefaultAndEmptySecretsInProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("jwt.secret", "schemaplexai-default-jwt-secret-key-32chars");
        environment.setProperty("schemaplexai.security.aes-key", "");
        environment.setProperty("spring.datasource.password", "");
        environment.setProperty("spring.data.redis.password", "");
        environment.setProperty("spring.rabbitmq.password", "");
        environment.setProperty("minio.secret-key", "");
        environment.setProperty("clickhouse.password", "clickhouse123");

        StartupSecurityValidator validator = new StartupSecurityValidator(buildProperties(false), environment);

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产环境安全配置不完整")
                .hasMessageContaining("jwt.secret")
                .hasMessageContaining("schemaplexai.security.aes-key")
                .hasMessageContaining("spring.datasource.password")
                .hasMessageContaining("spring.data.redis.password")
                .hasMessageContaining("spring.rabbitmq.password")
                .hasMessageContaining("minio.secret-key")
                .hasMessageContaining("clickhouse.password");
    }

    @Test
    void shouldAllowDevelopmentDefaultsWhenExplicitlyEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        environment.setProperty("jwt.secret", "schemaplexai-default-jwt-secret-key-32chars");
        environment.setProperty("schemaplexai.security.aes-key", "");

        StartupSecurityValidator validator = new StartupSecurityValidator(buildProperties(true), environment);

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    private SecurityProperties buildProperties(boolean allowDefaultSecretsInDev) {
        SecurityProperties properties = new SecurityProperties();
        properties.setAllowedOrigins(List.of("http://127.0.0.1:5173", "http://localhost:5173"));
        properties.setAllowDefaultSecretsInDev(allowDefaultSecretsInDev);
        properties.setActuatorPublicEndpoints(List.of("/actuator/health"));
        return properties;
    }
}
