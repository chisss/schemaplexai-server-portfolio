package com.schemaplexai.service.memory.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

/**
 * 文本清洗 DocumentTransformer
 *
 * <p>在文档分块之前执行清洗操作：
 * <ul>
 *   <li>去除非法 Unicode 字符（乱码、控制字符）</li>
 *   <li>合并多余空白行</li>
 *   <li>修复断行问题</li>
 * </ul>
 */
@Slf4j
public class CleaningDocumentTransformer implements DocumentTransformer {

    /** 匹配非法 Unicode 控制字符（保留换行 \n、回车 \r、制表 \t） */
    private static final Pattern ILLEGAL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** 匹配连续 3 个及以上空行 */
    private static final Pattern EXCESSIVE_BLANK_LINES = Pattern.compile("(\\n\\s*){3,}");

    /** 匹配行尾无意义断行（非句末标点/列表/标题后的换行） */
    private static final Pattern BROKEN_LINES = Pattern.compile("([^.!?。！？\\n:：\\-*#])\\n([a-zA-Z\\u4e00-\\u9fa5])");

    @Override
    public Document transform(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return document;
        }

        String cleaned = text;

        // 1. 去除非法控制字符
        cleaned = ILLEGAL_CHARS.matcher(cleaned).replaceAll("");

        // 2. 合并过多空行为两个换行
        cleaned = EXCESSIVE_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");

        // 3. 合并无意义断行（保留段落分隔）
        cleaned = BROKEN_LINES.matcher(cleaned).replaceAll("$1 $2");

        // 4. 去除首尾空白
        cleaned = cleaned.strip();

        if (cleaned.length() != text.length()) {
            log.debug("文档清洗完成: 原始长度={}, 清洗后长度={}", text.length(), cleaned.length());
        }

        return Document.from(cleaned, document.metadata());
    }
}
