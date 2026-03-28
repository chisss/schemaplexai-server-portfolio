package com.schemaplexai.service.tool.security;

import com.schemaplexai.common.constant.ToolConfigConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭据加密服务（AES-GCM）
 */
@Slf4j
@Service
public class CredentialEncryptionService {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom secureRandom = new SecureRandom();
    private SecretKeySpec secretKeySpec;

    @PostConstruct
    public void init() {
        String rawKey = System.getenv(ToolConfigConstant.ENV_TOOL_CONFIG_AES_KEY);
        if (!StringUtils.hasText(rawKey)) {
            log.warn("未检测到环境变量 {}，敏感配置写入将被拒绝",
                    ToolConfigConstant.ENV_TOOL_CONFIG_AES_KEY);
            return;
        }
        byte[] keyBytes = decodeKey(rawKey.trim());
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException(
                    "TOOL_CONFIG_AES_KEY 长度非法，必须为 16/24/32 字节（支持 Base64）");
        }
        this.secretKeySpec = new SecretKeySpec(keyBytes, ALGORITHM);
        log.info("工具配置加密服务初始化完成: keyLength={}", keyBytes.length);
    }

    /**
     * 加密明文
     */
    public String encrypt(String plainText) {
        if (!StringUtils.hasText(plainText)) {
            return plainText;
        }
        requireEncryptionKey();
        try {
            byte[] iv = new byte[ToolConfigConstant.AES_GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec,
                    new GCMParameterSpec(ToolConfigConstant.AES_GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new BusinessException(ResultCode.FAIL, "敏感配置加密失败");
        }
    }

    /**
     * 解密密文
     */
    public String decrypt(String cipherText) {
        if (!StringUtils.hasText(cipherText)) {
            return cipherText;
        }
        if (secretKeySpec == null) {
            return cipherText;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length <= ToolConfigConstant.AES_GCM_IV_LENGTH) {
                return cipherText;
            }
            byte[] iv = new byte[ToolConfigConstant.AES_GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - ToolConfigConstant.AES_GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKeySpec,
                    new GCMParameterSpec(ToolConfigConstant.AES_GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("检测到非加密格式或解密失败，按明文兼容处理");
            return cipherText;
        }
    }

    /**
     * 加密敏感字段
     */
    public Map<String, Object> encryptSensitiveFields(Map<String, Object> configValue, Map<String, Object> schema) {
        if (configValue == null || configValue.isEmpty()) {
            return Map.of();
        }
        return processSensitiveFields(configValue, schema, true);
    }

    /**
     * 解密敏感字段
     */
    public Map<String, Object> decryptSensitiveFields(Map<String, Object> configValue, Map<String, Object> schema) {
        if (configValue == null || configValue.isEmpty()) {
            return Map.of();
        }
        return processSensitiveFields(configValue, schema, false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> processSensitiveFields(Map<String, Object> value,
                                                        Map<String, Object> schema,
                                                        boolean encrypt) {
        Map<String, Object> processed = new LinkedHashMap<>(value.size());
        Map<String, Object> properties = schema == null ? Map.of()
                : (schema.get("properties") instanceof Map<?, ?> props
                ? (Map<String, Object>) props : Map.of());

        for (Map.Entry<String, Object> entry : value.entrySet()) {
            String field = entry.getKey();
            Object fieldValue = entry.getValue();
            Map<String, Object> fieldSchema = properties.get(field) instanceof Map<?, ?> raw
                    ? (Map<String, Object>) raw : Map.of();

            if (isSensitive(fieldSchema) && fieldValue instanceof String text) {
                processed.put(field, encrypt ? encrypt(text) : decrypt(text));
                continue;
            }
            if (fieldValue instanceof Map<?, ?> childMap) {
                processed.put(field, processSensitiveFields((Map<String, Object>) childMap, fieldSchema, encrypt));
                continue;
            }
            if (fieldValue instanceof List<?> childList) {
                Map<String, Object> itemSchema = fieldSchema.get("items") instanceof Map<?, ?> rawItem
                        ? (Map<String, Object>) rawItem : Map.of();
                processed.put(field, processSensitiveList(childList, itemSchema, encrypt));
                continue;
            }
            processed.put(field, fieldValue);
        }
        return processed;
    }

    @SuppressWarnings("unchecked")
    private List<Object> processSensitiveList(List<?> source, Map<String, Object> itemSchema, boolean encrypt) {
        List<Object> processed = new ArrayList<>(source.size());
        for (Object item : source) {
            if (item instanceof Map<?, ?> mapItem) {
                processed.add(processSensitiveFields((Map<String, Object>) mapItem, itemSchema, encrypt));
            } else if (item instanceof List<?> listItem) {
                processed.add(processSensitiveList(listItem, Map.of(), encrypt));
            } else {
                processed.add(item);
            }
        }
        return processed;
    }

    private boolean isSensitive(Map<String, Object> schema) {
        Object sensitive = schema.get("sensitive");
        return Boolean.TRUE.equals(sensitive);
    }

    private byte[] decodeKey(String rawKey) {
        try {
            return Base64.getDecoder().decode(rawKey);
        } catch (IllegalArgumentException ignored) {
            return rawKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    private void requireEncryptionKey() {
        if (secretKeySpec == null) {
            throw new BusinessException(ResultCode.CONFIG_NOT_FOUND,
                    "未配置工具加密密钥环境变量: " + ToolConfigConstant.ENV_TOOL_CONFIG_AES_KEY);
        }
    }
}
