package com.schemaplexai.service.database.credential.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecretCredentialMapper;
import com.schemaplexai.service.tool.security.CredentialEncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DatabaseCredentialVaultImplTest {

    private final SecretCredentialMapper mapper = mock(SecretCredentialMapper.class);
    private final CredentialEncryptionService encryptionService = mock(CredentialEncryptionService.class);
    private final DatabaseCredentialVaultImpl vault = new DatabaseCredentialVaultImpl(
            mapper, encryptionService, new ObjectMapper());

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    void shouldRejectCredentialWriteWithoutTenantContext() {
        when(encryptionService.encrypt(any())).thenReturn("cipher-text");

        assertThatThrownBy(() -> vault.save(null, Map.of("password", "secret")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        verifyNoInteractions(mapper);
    }
}
