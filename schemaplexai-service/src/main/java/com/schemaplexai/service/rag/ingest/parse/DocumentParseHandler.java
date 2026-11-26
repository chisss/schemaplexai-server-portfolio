package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.ingest.IngestCategory;

/**
 * 文档解析处理器。
 *
 * <p>每个 handler 只负责一类内容，由 {@link DocumentParseRouter} 按分类分发。
 * 新增格式（如 PPT）只需实现本接口并注册为 Bean，不改路由器。
 */
public interface DocumentParseHandler {

    /** 是否处理该分类 */
    boolean supports(IngestCategory category);

    /**
     * 是否作为"文本层过薄"的兜底解析器（如多模态 OCR）。
     * 扫描件 PDF 在网关阶段被判为 DOCUMENT，靠该标记在解析阶段转交 OCR。
     */
    default boolean supportsScannedFallback() {
        return false;
    }

    ParsedDocument parse(ParseContext context);
}
