package com.schemaplexai.service.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文件内容提取器
 */
@Slf4j
@Component
public class FileContentExtractor {

    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 6000;
    private static final Tika TIKA = new Tika();

    /**
     * 提取附件中的可读文本，优先使用 Tika，失败时回退到 UTF-8 文本读取。
     */
    public String extract(String fileName, InputStream inputStream) {
        if (inputStream == null) {
            return "";
        }
        try {
            byte[] bytes = readBytes(inputStream);
            if (bytes.length == 0) {
                return "";
            }
            String parsed = parseWithTika(fileName, bytes);
            if (StringUtils.hasText(parsed)) {
                return limitLength(parsed);
            }
            return limitLength(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception exception) {
            log.warn("提取附件内容失败: fileName={}, error={}", fileName, exception.getMessage());
            return "";
        }
    }

    private byte[] readBytes(InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readNBytes(MAX_READ_BYTES + 1);
        if (bytes.length > MAX_READ_BYTES) {
            byte[] truncated = new byte[MAX_READ_BYTES];
            System.arraycopy(bytes, 0, truncated, 0, MAX_READ_BYTES);
            return truncated;
        }
        return bytes;
    }

    private String parseWithTika(String fileName, byte[] bytes) {
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes)) {
            return TIKA.parseToString(byteArrayInputStream);
        } catch (Exception exception) {
            log.debug("Tika 解析附件失败，回退纯文本读取: fileName={}, error={}", fileName, exception.getMessage());
            return "";
        }
    }

    private String limitLength(String rawText) {
        if (!StringUtils.hasText(rawText)) {
            return "";
        }
        String normalized = rawText
                .replace("\u0000", "")
                .replaceAll("\\s{3,}", "\n\n")
                .trim();
        if (normalized.length() <= MAX_OUTPUT_CHARS) {
            return normalized;
        }
        return normalized.substring(0, MAX_OUTPUT_CHARS) + "\n\n[附件内容已截断]";
    }
}
