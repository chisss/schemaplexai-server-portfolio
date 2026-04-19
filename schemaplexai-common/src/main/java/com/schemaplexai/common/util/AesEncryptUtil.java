package com.schemaplexai.common.util;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-GCM 加密工具
 */
@Slf4j
public class AesEncryptUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private AesEncryptUtil() {
    }

    public static String encrypt(String plainText) {
        return encrypt(plainText, null);
    }

    public static String encrypt(String plainText, String secretKey) {
        if (!hasText(plainText)) {
            return plainText;
        }
        byte[] keyBytes = resolveKey(secretKey);
        if (keyBytes == null) {
            log.warn("未配置模型 AES 密钥，暂按 Base64 兼容写入");
            return Base64.getEncoder().encodeToString(plainText.getBytes(StandardCharsets.UTF_8));
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception exception) {
            throw new IllegalStateException("模型 API Key 加密失败", exception);
        }
    }

    public static String decrypt(String cipherText) {
        return decrypt(cipherText, null);
    }

    public static String decrypt(String cipherText, String secretKey) {
        if (!hasText(cipherText)) {
            return cipherText;
        }
        byte[] keyBytes = resolveKey(secretKey);
        if (keyBytes != null) {
            try {
                byte[] combined = Base64.getDecoder().decode(cipherText);
                if (combined.length > IV_LENGTH) {
                    byte[] iv = new byte[IV_LENGTH];
                    byte[] encrypted = new byte[combined.length - IV_LENGTH];
                    System.arraycopy(combined, 0, iv, 0, iv.length);
                    System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);
                    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, ALGORITHM), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
                    return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
                }
            } catch (Exception exception) {
                log.debug("模型 API Key 按 AES 解密失败，回退兼容 Base64/明文: {}", exception.getMessage());
            }
        }
        try {
            return new String(Base64.getDecoder().decode(cipherText), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return cipherText;
        }
    }

    private static byte[] resolveKey(String secretKey) {
        String rawKey = hasText(secretKey) ? secretKey.trim() : resolveDefaultSecretKey();
        if (!hasText(rawKey)) {
            return null;
        }
        byte[] keyBytes = decodeKey(rawKey);
        if (keyBytes.length != 16 && keyBytes.length != 24 && keyBytes.length != 32) {
            throw new IllegalStateException("模型 AES 密钥长度非法，必须为 16/24/32 字节");
        }
        return keyBytes;
    }

    private static byte[] decodeKey(String rawKey) {
        try {
            return Base64.getDecoder().decode(rawKey);
        } catch (IllegalArgumentException exception) {
            return rawKey.getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String resolveDefaultSecretKey() {
        String systemProperty = System.getProperty("schemaplexai.security.aes-key");
        if (hasText(systemProperty)) {
            return systemProperty.trim();
        }
        String envValue = System.getenv("AES_SECRET_KEY");
        if (hasText(envValue)) {
            return envValue.trim();
        }
        return null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
