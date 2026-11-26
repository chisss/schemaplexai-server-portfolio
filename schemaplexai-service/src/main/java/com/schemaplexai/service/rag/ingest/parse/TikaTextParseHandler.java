package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.ingest.GatewayDecision;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Tika 文本解析（原生 PDF / Word / txt / md / 源码）。
 *
 * <p>设计稿给 Tika 的定位是"文本层提取 + 代码原文提取"，表格结构不是它的强项：
 * PDF/Word 内嵌复杂表格会扁平化，因此这里只保证"文本尽可能完整 + 不超过内存上限"，
 * 表格类内容由 Excel 分支与切分阶段的表格块标记来兜底。
 */
@Slf4j
@Component
public class TikaTextParseHandler implements DocumentParseHandler {

    /** 单文档最大提取字符数，防止超大 PDF 打爆堆内存（约 8M 字符） */
    static final int MAX_CHARS = 8_000_000;

    private static final AutoDetectParser TIKA_PARSER = new AutoDetectParser();

    @Override
    public boolean supports(IngestCategory category) {
        return category == IngestCategory.DOCUMENT || category == IngestCategory.CODE;
    }

    @Override
    public ParsedDocument parse(ParseContext context) {
        try {
            BodyContentHandler handler = new BodyContentHandler(new WriteOutContentHandler(MAX_CHARS));
            Metadata metadata = new Metadata();
            if (context.fileName() != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, context.fileName());
            }
            try (InputStream input = new ByteArrayInputStream(context.content())) {
                TIKA_PARSER.parse(input, handler, metadata, new org.apache.tika.parser.ParseContext());
            }

            String extracted = handler.toString();
            String text = TextNormalizer.normalize(extracted);
            boolean truncated = extracted.length() >= MAX_CHARS;
            List<String> warnings = truncated
                    ? List.of("文本超过 " + MAX_CHARS + " 字符已截断，后续片段可能不完整")
                    : List.of();

            return new ParsedDocument(text, GatewayDecision.PARSER_TIKA, context.category(),
                    Map.of("parser", GatewayDecision.PARSER_TIKA,
                            "charCount", text.length(),
                            "truncated", truncated),
                    warnings, false);
        } catch (Exception e) {
            log.warn("Tika 解析失败: fileName={}, error={}", context.fileName(), e.getMessage());
            throw new DocumentParseException(DocumentParseException.PARSE_ERROR,
                    "文档解析失败: " + e.getMessage(), e);
        }
    }

    /** 供 OCR 分支判断"文本层是否薄到需要走 OCR" */
    static int scannedTextThreshold(ParseContext context) {
        int configured = context.settings() != null ? context.settings().getOcrMinTextChars() : 0;
        return configured > 0 ? configured : RagDefaults.OCR_MIN_TEXT_CHARS;
    }
}
