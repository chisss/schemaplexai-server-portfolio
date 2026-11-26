package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 解析路由：主解析器选择、扫描件 PDF 回退 OCR、无 handler 显式报错。
 */
class DocumentParseRouterTest {

    @Test
    @DisplayName("文本类文件走 Tika，原文可读")
    void shouldParseTextWithTika() {
        DocumentParseRouter router = new DocumentParseRouter(List.of(new TikaTextParseHandler()));
        String source = "public class PaymentService { void pay() {} }";

        ParsedDocument parsed = router.parse(new ParseContext("PaymentService.java", "text/plain",
                IngestCategory.CODE, source.getBytes(StandardCharsets.UTF_8), settings()));

        assertThat(parsed.text()).contains("PaymentService");
        assertThat(parsed.parserId()).isEqualTo("tika");
        assertThat(parsed.scannedFallback()).isFalse();
    }

    @Test
    @DisplayName("PDF 文本层过薄时自动转交 OCR 兜底处理器")
    void shouldFallbackToOcrWhenPdfTextIsTooThin() {
        AtomicBoolean ocrCalled = new AtomicBoolean(false);
        DocumentParseHandler shortText = stubHandler(IngestCategory.DOCUMENT, "tika",
                "  ", false);
        DocumentParseHandler ocr = new DocumentParseHandler() {
            @Override
            public boolean supports(IngestCategory category) {
                return category == IngestCategory.IMAGE;
            }

            @Override
            public boolean supportsScannedFallback() {
                return true;
            }

            @Override
            public ParsedDocument parse(ParseContext context) {
                ocrCalled.set(true);
                return new ParsedDocument("OCR 识别出的合同正文", "multimodal-ocr", IngestCategory.IMAGE,
                        Map.of("ocr_source", "multimodal_model"), List.of(), false);
            }
        };
        DocumentParseRouter router = new DocumentParseRouter(List.of(shortText, ocr));

        ParsedDocument parsed = router.parse(new ParseContext("扫描合同.pdf", "application/pdf",
                IngestCategory.DOCUMENT, new byte[]{1, 2, 3}, settings()));

        assertThat(ocrCalled).isTrue();
        assertThat(parsed.text()).contains("OCR 识别出的合同正文");
        assertThat(parsed.parserId()).isEqualTo("multimodal-ocr");
    }

    @Test
    @DisplayName("没有 OCR 处理器时明确标记为扫描件待人工处理，而不是静默返回空文本")
    void shouldMarkScannedWhenNoOcrHandler() {
        DocumentParseHandler shortText = stubHandler(IngestCategory.DOCUMENT, "tika", "", false);
        DocumentParseRouter router = new DocumentParseRouter(List.of(shortText));

        ParsedDocument parsed = router.parse(new ParseContext("扫描合同.pdf", "application/pdf",
                IngestCategory.DOCUMENT, new byte[]{1}, settings()));

        assertThat(parsed.scannedFallback()).isTrue();
        assertThat(parsed.warnings()).anyMatch(w -> w.contains("疑似扫描件"));
    }

    @Test
    @DisplayName("Word 文本为空不触发 OCR（避免白烧算力）")
    void shouldNotFallbackForNonPdf() {
        AtomicBoolean ocrCalled = new AtomicBoolean(false);
        DocumentParseHandler emptyDoc = stubHandler(IngestCategory.DOCUMENT, "tika", "", false);
        DocumentParseHandler ocr = new DocumentParseHandler() {
            @Override
            public boolean supports(IngestCategory category) {
                return category == IngestCategory.IMAGE;
            }

            @Override
            public boolean supportsScannedFallback() {
                return true;
            }

            @Override
            public ParsedDocument parse(ParseContext context) {
                ocrCalled.set(true);
                return ParsedDocument.of("x", "multimodal-ocr", IngestCategory.IMAGE);
            }
        };
        DocumentParseRouter router = new DocumentParseRouter(List.of(emptyDoc, ocr));

        ParsedDocument parsed = router.parse(new ParseContext("空文档.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                IngestCategory.DOCUMENT, new byte[]{1}, settings()));

        assertThat(ocrCalled).isFalse();
        assertThat(parsed.scannedFallback()).isFalse();
    }

    @Test
    @DisplayName("分类无对应 handler 时报 NO_HANDLER，不返回空结果")
    void shouldThrowWhenNoHandler() {
        DocumentParseRouter router = new DocumentParseRouter(List.of(new TikaTextParseHandler()));

        assertThatThrownBy(() -> router.parse(new ParseContext("a.xlsx", "text/csv",
                IngestCategory.EXCEL, new byte[]{1}, settings())))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("没有可用的解析器");
    }

    private DocumentParseHandler stubHandler(IngestCategory category, String parserId, String text, boolean fallback) {
        return new DocumentParseHandler() {
            @Override
            public boolean supports(IngestCategory target) {
                return target == category;
            }

            @Override
            public boolean supportsScannedFallback() {
                return fallback;
            }

            @Override
            public ParsedDocument parse(ParseContext context) {
                return ParsedDocument.of(text, parserId, category);
            }
        };
    }

    private RagRuntimeSettings settings() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .ocrMinTextChars(20)
                .build();
    }
}
