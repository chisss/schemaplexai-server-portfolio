package com.schemaplexai.service.rag.ingest.ocr;

/**
 * OCR 请求。一页一张图，避免把整份 PDF 塞进一次模型调用。
 *
 * @param imageBytes       图片字节（PNG/JPEG）
 * @param mimeType         图片 MIME
 * @param fileName         原始文件名（用于提示与审计）
 * @param pageIndex        页码（图片为 0）
 * @param ocrModelConfigId 多模态模型配置 ID（sf_ai_model.id）
 */
public record OcrRequest(byte[] imageBytes,
                         String mimeType,
                         String fileName,
                         int pageIndex,
                         String ocrModelConfigId) {
}
