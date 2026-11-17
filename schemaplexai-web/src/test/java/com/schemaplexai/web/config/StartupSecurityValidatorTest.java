package com.schemaplexai.web.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupSecurityValidatorTest {

    private final Environment environment = mock(Environment.class);
    private final StartupSecurityValidator validator = new StartupSecurityValidator(new SecurityProperties(), environment);

    @Test
    void shouldRejectDefaultJwtSecretInProd() {
        givenProd();
        givenAllSecretsValid();
        when(environment.getProperty("jwt.secret")).thenReturn("schemaplexai-default-jwt-secret-key-32chars");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void shouldRejectWeakJwtSecretInProd() {
        givenProd();
        givenAllSecretsValid();
        when(environment.getProperty("jwt.secret")).thenReturn("short-key");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长度不足");
    }

    @Test
    void shouldRejectExplicitWeakQuerySigningSecretInProd() {
        givenProd();
        givenAllSecretsValid();
        when(environment.getProperty("schemaplexai.semantic.query-signing-secret")).thenReturn("weak");

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("query-signing-secret");
    }

    @Test
    void shouldPassWithAllSecretsValidInProd() {
        givenProd();
        givenAllSecretsValid();

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    void shouldSkipValidationInDevProfile() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"dev"});

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    private void givenProd() {
        when(environment.getActiveProfiles()).thenReturn(new String[]{"prod"});
    }

    private void givenAllSecretsValid() {
        String strong = "x".repeat(40);
        when(environment.getProperty("jwt.secret")).thenReturn(strong);
        when(environment.getProperty("schemaplexai.security.aes-key")).thenReturn(strong);
        when(environment.getProperty("spring.datasource.password")).thenReturn("db-pass");
        when(environment.getProperty("spring.data.redis.password")).thenReturn("redis-pass");
        when(environment.getProperty("spring.rabbitmq.password")).thenReturn("mq-pass");
        when(environment.getProperty("minio.secret-key")).thenReturn("minio-pass");
        when(environment.getProperty("clickhouse.password")).thenReturn("ch-pass");
        when(environment.getProperty("schemaplexai.semantic.query-signing-secret")).thenReturn(null);
    }
}
