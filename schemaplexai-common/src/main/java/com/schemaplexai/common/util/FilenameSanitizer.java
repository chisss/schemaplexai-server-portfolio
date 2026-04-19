package com.schemaplexai.common.util;

import lombok.experimental.UtilityClass;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * 文件名脱敏工具：剔除不安全字符，防止目录穿越与对象键注入。
 */
@UtilityClass
public class FilenameSanitizer {

    /** 默认最大保留长度（字符） */
    private static final int DEFAULT_MAX_LENGTH = 120;

    /** 非法字符：路径分隔符、控制字符、Windows 保留字符 */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[\\x00-\\x1F\\x7F/\\\\:*?\"<>|]");

    /** 连续空白与下划线归一化 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 清洗文件名：去除路径片段、非法字符与多余空白；超长则截断扩展名前部分。
     *
     * @param original 原始文件名，允许为 null
     * @return 脱敏后的文件名；若原始名为空则返回 {@code "unnamed"}
     */
    public static String sanitize(String original) {
        if (original == null || original.isBlank()) {
            return "unnamed";
        }

        // 仅保留最后一段（防止 "../" 之类）
        String name = original.replace('\\', '/');
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }

        // Unicode 归一化
        name = Normalizer.normalize(name, Normalizer.Form.NFKC);

        // 非法字符替换为下划线
        name = ILLEGAL_CHARS.matcher(name).replaceAll("_");
        name = WHITESPACE.matcher(name).replaceAll("_");

        // 去除首尾 "." 与 "_"
        name = name.replaceAll("^[._]+", "").replaceAll("[._]+$", "");

        if (name.isBlank()) {
            return "unnamed";
        }

        // 截断：尽量保留扩展名
        if (name.length() > DEFAULT_MAX_LENGTH) {
            int dotIdx = name.lastIndexOf('.');
            if (dotIdx > 0 && dotIdx > name.length() - 10) {
                String ext = name.substring(dotIdx);
                name = name.substring(0, DEFAULT_MAX_LENGTH - ext.length()) + ext;
            } else {
                name = name.substring(0, DEFAULT_MAX_LENGTH);
            }
        }

        // 确保 ASCII 级别安全（为对象键/HTTP header 保护）
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 512) {
            name = new String(bytes, 0, 512, StandardCharsets.UTF_8);
        }
        return name;
    }

    /**
     * 提取小写扩展名（不含点）；无扩展名返回 {@code "unknown"}。
     */
    public static String extractExtension(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == fileName.length() - 1) {
            return "unknown";
        }
        return fileName.substring(dotIdx + 1).toLowerCase();
    }
}
