package com.schemaplexai.service.rag.ingest;

import java.util.List;
import java.util.Map;

/**
 * 文件网关判定结果。
 *
 * <p>只有 {@code accepted=true} 时才允许进入解析阶段；被拒时 {@link #rejectCode()}
 * 是稳定枚举值（便于告警聚合），{@link #rejectReason()} 面向用户可读。
 */
public record GatewayDecision(boolean accepted,
                              IngestCategory category,
                              String detectedMime,
                              String parserId,
                              String rejectCode,
                              String rejectReason,
                              List<String> warnings,
                              Map<String, Integer> privacyCounters) {

    /** 拒绝原因码 */
    public static final String REJECT_TOO_LARGE = "TOO_LARGE";
    public static final String REJECT_BLOCKED_TYPE = "BLOCKED_TYPE";
    public static final String REJECT_ENCRYPTED = "ENCRYPTED";
    public static final String REJECT_CATEGORY_DISABLED = "CATEGORY_DISABLED";
    public static final String REJECT_COMPLIANCE = "COMPLIANCE_BLOCKED";
    public static final String REJECT_EMPTY = "EMPTY_FILE";

    /** 解析器标识，与 {@code KnowledgeDocument.sourceParser} 落库值一致 */
    public static final String PARSER_TIKA = "tika";
    public static final String PARSER_EXCEL = "excel-poi";
    public static final String PARSER_OCR = "multimodal-ocr";

    public static GatewayDecision accept(IngestCategory category, String mime, String parserId,
                                         List<String> warnings, Map<String, Integer> counters) {
        return new GatewayDecision(true, category, mime, parserId, null, null, warnings, counters);
    }

    public static GatewayDecision reject(String code, String reason) {
        return new GatewayDecision(false, null, null, null, code, reason, List.of(), Map.of());
    }
}
