package com.schemaplexai.service.memory.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentTransformer;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 文本清洗 DocumentTransformer
 *
 * <p>在文档分块之前执行清洗操作：
 * <ul>
 *   <li>去除零宽字符与 BOM</li>
 *   <li>修复常见编码伪影（mojibake）</li>
 *   <li>去除非法 Unicode 控制字符</li>
 *   <li>移除独立行页码</li>
 *   <li>检测并移除重复页眉/页脚</li>
 *   <li>归一化行内多余空白</li>
 *   <li>合并多余空白行</li>
 *   <li>修复断行问题</li>
 * </ul>
 */
@Slf4j
public class CleaningDocumentTransformer implements DocumentTransformer {

    /** 零宽字符与 BOM */
    private static final Pattern ZERO_WIDTH_CHARS =
            Pattern.compile("[\\u200B-\\u200F\\u2028-\\u202F\\uFEFF]");

    /** 非法 Unicode 控制字符（保留换行 \n、回车 \r、制表 \t） */
    private static final Pattern ILLEGAL_CHARS =
            Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** 独立行页码（纯数字行，常见于 PDF 提取） */
    private static final Pattern PAGE_NUMBER_LINE =
            Pattern.compile("(?m)^\\s*\\d{1,4}\\s*$");

    /** 行内连续空白归一化 */
    private static final Pattern EXCESSIVE_INLINE_WHITESPACE =
            Pattern.compile("[\\t ]{2,}");

    /** 连续 3 个及以上空行 */
    private static final Pattern EXCESSIVE_BLANK_LINES =
            Pattern.compile("(\\n\\s*){3,}");

    /** 行尾无意义断行（非句末标点/列表/标题后的换行） */
    private static final Pattern BROKEN_LINES =
            Pattern.compile("([^.!?。！？\\n:：\\-*#])\\n([a-zA-Z\\u4e00-\\u9fa5])");

    /** 常见 mojibake 编码伪影修复映射 */
    private static final Map<String, String> ENCODING_FIXES = new HashMap<>();

    /** 重复页眉/页脚检测阈值：同一短行出现次数 >= 此值则移除 */
    private static final int REPEATED_LINE_THRESHOLD = 3;

    /** 页眉/页脚候选行最大长度 */
    private static final int HEADER_FOOTER_MAX_LENGTH = 80;

    static {
        ENCODING_FIXES.put("\u00C3\u00A2\u00E2\u0082\u00AC\u00E2\u0084\u00A2", "'");
        ENCODING_FIXES.put("\u00E2\u0080\u0099", "\u2019");
        ENCODING_FIXES.put("\u00E2\u0080\u009C", "\u201C");
        ENCODING_FIXES.put("\u00E2\u0080\u009D", "\u201D");
        ENCODING_FIXES.put("\u00E2\u0080\u0093", "\u2013");
        ENCODING_FIXES.put("\u00E2\u0080\u0094", "\u2014");
        ENCODING_FIXES.put("\u00E2\u0080\u00A6", "\u2026");
        ENCODING_FIXES.put("\u00C2\u00A0", " ");
    }

    @Override
    public Document transform(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return document;
        }

        String cleaned = text;

        // 1. 零宽字符与 BOM
        cleaned = ZERO_WIDTH_CHARS.matcher(cleaned).replaceAll("");

        // 2. 编码伪影修复
        for (Map.Entry<String, String> entry : ENCODING_FIXES.entrySet()) {
            if (cleaned.contains(entry.getKey())) {
                cleaned = cleaned.replace(entry.getKey(), entry.getValue());
            }
        }

        // 3. 非法控制字符
        cleaned = ILLEGAL_CHARS.matcher(cleaned).replaceAll("");

        // 4. 独立行页码移除
        cleaned = PAGE_NUMBER_LINE.matcher(cleaned).replaceAll("");

        // 5. 重复页眉/页脚检测与移除
        cleaned = removeRepeatedHeaderFooter(cleaned);

        // 6. 行内多余空白归一化
        cleaned = EXCESSIVE_INLINE_WHITESPACE.matcher(cleaned).replaceAll(" ");

        // 7. 合并过多空行
        cleaned = EXCESSIVE_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");

        // 8. 合并无意义断行
        cleaned = BROKEN_LINES.matcher(cleaned).replaceAll("$1 $2");

        // 9. 首尾空白
        cleaned = cleaned.strip();

        if (cleaned.length() != text.length()) {
            log.debug("文档清洗完成: 原始长度={}, 清洗后长度={}", text.length(), cleaned.length());
        }

        return Document.from(cleaned, document.metadata());
    }

    /**
     * 统计各行出现频率，移除出现 >= REPEATED_LINE_THRESHOLD 次的短行（疑似页眉/页脚）
     */
    private String removeRepeatedHeaderFooter(String text) {
        String[] lines = text.split("\n");
        if (lines.length < REPEATED_LINE_THRESHOLD * 2) {
            return text;
        }

        Map<String, Integer> lineFrequency = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && trimmed.length() <= HEADER_FOOTER_MAX_LENGTH) {
                lineFrequency.merge(trimmed, 1, Integer::sum);
            }
        }

        StringBuilder sb = new StringBuilder(text.length());
        for (String line : lines) {
            String trimmed = line.strip();
            Integer freq = lineFrequency.get(trimmed);
            if (freq != null && freq >= REPEATED_LINE_THRESHOLD
                    && trimmed.length() <= HEADER_FOOTER_MAX_LENGTH) {
                continue;
            }
            sb.append(line).append('\n');
        }

        return sb.toString();
    }
}
