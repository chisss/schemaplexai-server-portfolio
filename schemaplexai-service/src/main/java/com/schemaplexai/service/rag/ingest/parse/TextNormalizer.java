package com.schemaplexai.service.rag.ingest.parse;

import java.util.regex.Pattern;

/**
 * 解析文本归一化（所有 handler 共用）。
 *
 * <p>只做"无争议"的清洗：分页符、页眉页脚残留的分隔线、零宽字符、连续空行。
 * 语义级清洗（去目录、去表格碎片）放到切分器，避免这里把有效信息删掉。
 */
public final class TextNormalizer {

    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+\\n");
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\uFEFF]");
    private static final Pattern SEPARATOR_LINE = Pattern.compile("(?m)^[-=_*]{3,}\\s*$");

    private TextNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replace('\f', '\n');
        text = ZERO_WIDTH.matcher(text).replaceAll("");
        text = SEPARATOR_LINE.matcher(text).replaceAll("");
        text = TRAILING_SPACES.matcher(text).replaceAll("\n");
        text = MULTI_BLANK_LINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }
}
