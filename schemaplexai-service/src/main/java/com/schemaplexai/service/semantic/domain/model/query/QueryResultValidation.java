package com.schemaplexai.service.semantic.domain.model.query;

import java.time.Instant;

/** 一次查询的技术结果检查与独立业务对账证据。 */
public record QueryResultValidation(
        TechnicalStatus technicalStatus,
        ResultStatus resultStatus,
        BusinessStatus businessStatus,
        String businessReason,
        Instant checkedAt,
        String baselineLabel,
        Integer comparedRows,
        Integer missingReferenceGroups,
        Integer extraReferenceGroups,
        Integer differingGroups,
        ComparedWindow comparedWindow) {

    public record ComparedWindow(String from, String to, String timeZone) {}

    public QueryResultValidation(TechnicalStatus technicalStatus, ResultStatus resultStatus, BusinessStatus businessStatus,
                                 String businessReason, Instant checkedAt, String baselineLabel, Integer comparedRows) {
        this(technicalStatus, resultStatus, businessStatus, businessReason, checkedAt, baselineLabel, comparedRows,
                null, null, null, null);
    }

    public enum TechnicalStatus { PASSED, NOT_CHECKED }
    public enum ResultStatus { COMPLETE, EMPTY, TRUNCATED }
    public enum BusinessStatus { MATCHED, MISMATCH, NOT_CHECKED }
}
