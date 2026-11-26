package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.ingest.IngestCategory;

import java.util.List;
import java.util.Map;

/**
 * 解析结果：统一为「文本 + 元数据」。
 *
 * <p>Excel 的解析结果刻意转成 Markdown 表格而非二维结构，原因是下游切分器与 LLM 都读文本，
 * 表格一旦结构化成 JSON 反而要在 Prompt 里重建语义。保留 Markdown 也让用户能在引用块里直接看懂。
 *
 * @param text            解析文本（可能为空，见 {@code scannedFallback}）
 * @param parserId        实际解析器标识（落库 sourceParser）
 * @param category        分类
 * @param metadata        附加元数据（sheet 数、行数、是否截断等）
 * @param warnings        非致命提示
 * @param scannedFallback 是否为"文本层过薄、需转 OCR"的扫描件
 */
public record ParsedDocument(String text,
                             String parserId,
                             IngestCategory category,
                             Map<String, Object> metadata,
                             List<String> warnings,
                             boolean scannedFallback) {

    public static ParsedDocument of(String text, String parserId, IngestCategory category) {
        return new ParsedDocument(text, parserId, category, Map.of(), List.of(), false);
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
