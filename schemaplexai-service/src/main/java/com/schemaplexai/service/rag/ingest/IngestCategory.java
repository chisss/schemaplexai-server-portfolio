package com.schemaplexai.service.rag.ingest;

import java.util.Locale;

/**
 * 文档分类路由标签。
 *
 * <p>分类决定后续解析器与切分策略，是整条摄入管线的分叉点：
 * <ul>
 *   <li>{@link #CODE}：源码文件，走 Tika 原文提取 + AST 切分</li>
 *   <li>{@link #DOCUMENT}：PDF/Word/txt/md 等，走 Tika + 章节语义切分</li>
 *   <li>{@link #EXCEL}：表格，走 POI 转 Markdown + 表格块切分</li>
 *   <li>{@link #IMAGE}：图片或扫描件，走多模态 OCR + 章节语义切分</li>
 * </ul>
 */
public enum IngestCategory {

    CODE("code"),
    DOCUMENT("document"),
    EXCEL("excel"),
    IMAGE("image");

    private final String code;

    IngestCategory(String code) {
        this.code = code;
    }

    /** 落库/埋点用的稳定小写值 */
    public String code() {
        return code;
    }

    /** 从字符串宽松解析，未知值回落 DOCUMENT（宁可当普通文档，也不静默丢弃） */
    public static IngestCategory fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            return DOCUMENT;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (IngestCategory category : values()) {
            if (category.code.equals(normalized) || category.name().equalsIgnoreCase(normalized)) {
                return category;
            }
        }
        return DOCUMENT;
    }
}
