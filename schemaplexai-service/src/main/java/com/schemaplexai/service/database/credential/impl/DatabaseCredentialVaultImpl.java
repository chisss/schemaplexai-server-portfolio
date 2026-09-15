package com.schemaplexai.service.database.credential.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecretCredentialMapper;
import com.schemaplexai.model.entity.SecretCredential;
import com.schemaplexai.service.database.credential.DatabaseCredentialVault;
import com.schemaplexai.service.tool.security.CredentialEncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 基于 PostgreSQL 和 AES-GCM 的数据库凭据仓库
 */
@Service
@RequiredArgsConstructor
public class DatabaseCredentialVaultImpl implements DatabaseCredentialVault {

    private static final String CREDENTIAL_TYPE = "database";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_DELETED = "deleted";

    private final SecretCredentialMapper secretCredentialMapper;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String save(String secretRef, Map<String, Object> credential) {
        if (credential == null || credential.isEmpty()) {
            return secretRef;
        }
        String cipherText = credentialEncryptionService.encrypt(writeCredential(credential));
        if (StringUtils.hasText(secretRef)) {
            SecretCredential existing = requireCredential(secretRef);
            existing.setCipherText(cipherText);
            existing.setStatus(STATUS_ACTIVE);
            secretCredentialMapper.updateById(existing);
            return existing.getId();
        }

        SecretCredential created = new SecretCredential();
        created.setTenantId(requireTenantId());
        created.setCredentialType(CREDENTIAL_TYPE);
        created.setCipherText(cipherText);
        created.setStatus(STATUS_ACTIVE);
        secretCredentialMapper.insert(created);
        return created.getId();
    }

    @Override
    public Map<String, Object> resolve(String secretRef) {
        if (!StringUtils.hasText(secretRef)) {
            return Map.of();
        }
        SecretCredential credential = requireCredential(secretRef);
        String plainText = credentialEncryptionService.decrypt(credential.getCipherText());
        try {
            return objectMapper.readValue(plainText, new TypeReference<>() {
            });
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.FAIL, "数据库凭据解析失败");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String secretRef) {
        if (!StringUtils.hasText(secretRef)) {
            return;
        }
        SecretCredential credential = requireCredential(secretRef);
        credential.setStatus(STATUS_DELETED);
        secretCredentialMapper.updateById(credential);
    }

    private SecretCredential requireCredential(String secretRef) {
        SecretCredential credential = secretCredentialMapper.selectOne(
                new LambdaQueryWrapper<SecretCredential>()
                        .eq(SecretCredential::getId, secretRef.trim())
                        .eq(SecretCredential::getTenantId, requireTenantId())
                        .eq(SecretCredential::getCredentialType, CREDENTIAL_TYPE)
                        .eq(SecretCredential::getStatus, STATUS_ACTIVE)
                        .last("LIMIT 1")
        );
        if (credential == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND, "数据库凭据不存在或无权访问");
        }
        return credential;
    }

    private String writeCredential(Map<String, Object> credential) {
        try {
            return objectMapper.writeValueAsString(new LinkedHashMap<>(credential));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "数据库凭据格式无效");
        }
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }
}
