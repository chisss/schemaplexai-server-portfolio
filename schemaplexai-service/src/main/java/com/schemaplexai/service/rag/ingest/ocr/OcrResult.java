package com.schemaplexai.service.rag.ingest.ocr;

/**
 * OCR 结果。
 *
 * @param text       识别文本
 * @param confidence 置信度（0~1）；模型不返回时用文本长度做代理，见 handler
 * @param source     来源标识：multimodal_model / external_api
 */
public record OcrResult(String text, double confidence, String source) {

    public static final String SOURCE_MULTIMODAL = "multimodal_model";
    public static final String SOURCE_EXTERNAL_API = "external_api";

    public boolean hasText() {
        return text != null && !text.isBlank();
    }
}
