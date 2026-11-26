package com.schemaplexai.service.rag.ingest.parse;

/**
 * 解析失败（区分于"解析成功但内容为空"）。
 *
 * <p>{@code code} 用于审计与告警聚合：NO_HANDLER / PARSE_ERROR / OCR_FAILED / OCR_LOW_CONFIDENCE。
 */
public class DocumentParseException extends RuntimeException {

    public static final String NO_HANDLER = "NO_HANDLER";
    public static final String PARSE_ERROR = "PARSE_ERROR";
    public static final String OCR_FAILED = "OCR_FAILED";
    public static final String OCR_LOW_CONFIDENCE = "OCR_LOW_CONFIDENCE";

    private final String code;

    public DocumentParseException(String code, String message) {
        super(message);
        this.code = code;
    }

    public DocumentParseException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
