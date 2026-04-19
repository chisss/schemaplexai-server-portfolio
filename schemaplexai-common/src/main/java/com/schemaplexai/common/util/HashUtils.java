package com.schemaplexai.common.util;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希工具：目前提供 SHA-256 计算，用于内容级去重。
 */
@UtilityClass
public class HashUtils {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
    private static final int BUFFER_SIZE = 8 * 1024;

    /**
     * 计算字节数组的 SHA-256（小写 hex）。
     */
    public static String sha256Hex(byte[] data) {
        if (data == null) {
            return null;
        }
        MessageDigest digest = newSha256();
        return toHex(digest.digest(data));
    }

    /**
     * 流式计算 SHA-256（小写 hex）。调用方负责关闭传入流。
     */
    public static String sha256Hex(InputStream input) throws IOException {
        if (input == null) {
            return null;
        }
        MessageDigest digest = newSha256();
        byte[] buffer = new byte[BUFFER_SIZE];
        int n;
        while ((n = input.read(buffer)) > 0) {
            digest.update(buffer, 0, n);
        }
        return toHex(digest.digest());
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // JDK 标配，不应触发
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0, j = 0; i < bytes.length; i++) {
            out[j++] = HEX_CHARS[(bytes[i] >>> 4) & 0x0F];
            out[j++] = HEX_CHARS[bytes[i] & 0x0F];
        }
        return new String(out);
    }
}
