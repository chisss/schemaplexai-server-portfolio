package com.schemaplexai.service.rag.ingest.ocr;

/**
 * OCR 调用失败（区别于"识别成功但结果过短"）。
 */
public class OcrException extends RuntimeException {

    private final String code;

    public OcrException(String code, String message) {
        super(message);
        this.code = code;
    }

    public OcrException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
