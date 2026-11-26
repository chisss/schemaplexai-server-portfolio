package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.ingest.IngestCategory;
import com.schemaplexai.service.rag.ingest.IngestFileTypes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 解析路由器。
 *
 * <p>两级路由：
 * <ol>
 *   <li>按 {@link IngestCategory} 选主解析器（CODE/DOCUMENT→Tika，EXCEL→POI，IMAGE→OCR）；</li>
 *   <li>主解析结果"文本层过薄"且是 PDF 时，转交支持扫描件兜底的 handler（多模态 OCR）。
 *       这一步很关键：网关无法在不解析的情况下知道 PDF 是扫描件，只能在解析后回退。</li>
 * </ol>
 *
 * <p>语义边界：路由器不做清洗、不做切分，只负责"谁解析、解析后要不要换人"。
 */
@Slf4j
@Component
public class DocumentParseRouter {

    private final List<DocumentParseHandler> handlers;

    public DocumentParseRouter(List<DocumentParseHandler> handlers) {
        this.handlers = handlers == null ? List.of() : List.copyOf(handlers);
    }

    public ParsedDocument parse(ParseContext context) {
        DocumentParseHandler primary = resolvePrimary(context.category());
        ParsedDocument parsed = primary.parse(context);

        if (!shouldFallbackToOcr(context, parsed)) {
            return parsed;
        }

        Optional<DocumentParseHandler> fallback = handlers.stream()
                .filter(DocumentParseHandler::supportsScannedFallback)
                .findFirst();
        if (fallback.isEmpty()) {
            int charCount = parsed.text() == null ? 0 : parsed.text().length();
            log.warn("检测到疑似扫描件但无 OCR 兜底处理器: fileName={}, charCount={}",
                    context.fileName(), charCount);
            List<String> warnings = new ArrayList<>(parsed.warnings());
            warnings.add("文本层仅 " + charCount + " 字符，疑似扫描件；当前未配置 OCR，无法提取内容");
            return new ParsedDocument(parsed.text(), parsed.parserId(), parsed.category(),
                    parsed.metadata(), warnings, true);
        }

        log.info("PDF 文本层过薄，转多模态 OCR: fileName={}", context.fileName());
        ParsedDocument ocrParsed = fallback.get().parse(context.withCategory(IngestCategory.IMAGE));
        List<String> warnings = new ArrayList<>(parsed.warnings());
        warnings.addAll(ocrParsed.warnings());
        return new ParsedDocument(ocrParsed.text(), ocrParsed.parserId(), IngestCategory.IMAGE,
                ocrParsed.metadata(), warnings, false);
    }

    private DocumentParseHandler resolvePrimary(IngestCategory category) {
        return handlers.stream()
                .filter(handler -> handler.supports(category))
                .findFirst()
                .orElseThrow(() -> new DocumentParseException(DocumentParseException.NO_HANDLER,
                        "没有可用的解析器处理分类: " + category.code()));
    }

    /**
     * 是否应转 OCR：
     * 仅限「PDF 且文本层字符数低于阈值」，不覆盖 Word/txt —— 那些文件文本为空通常是真的空文件，
     * 送去 OCR 只会白烧算力。
     */
    private boolean shouldFallbackToOcr(ParseContext context, ParsedDocument parsed) {
        if (context.category() != IngestCategory.DOCUMENT) {
            return false;
        }
        if (!StringUtils.hasText(context.mimeType())
                || !IngestFileTypes.MIME_PDF.equalsIgnoreCase(context.mimeType())) {
            return false;
        }
        int threshold = TikaTextParseHandler.scannedTextThreshold(context);
        int charCount = parsed.text() == null ? 0 : parsed.text().replaceAll("\\s", "").length();
        return charCount < threshold;
    }
}
