package com.schemaplexai.model.vo.semantic;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/** 客户端可见的本次查询校验与对账证据。 */
public record SemanticQueryValidationVO(
        String technicalStatus,
        String resultStatus,
        String businessStatus,
        String businessReason,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Instant checkedAt,
        String baselineLabel,
        Integer comparedRows,
        Integer missingReferenceGroups,
        Integer extraReferenceGroups,
        Integer differingGroups,
        QueryComparedWindow comparedWindow) {

    public record QueryComparedWindow(String from, String to, String timeZone) {}

    public SemanticQueryValidationVO(String technicalStatus, String resultStatus, String businessStatus,
                                     String businessReason, Instant checkedAt, String baselineLabel,
                                     Integer comparedRows) {
        this(technicalStatus, resultStatus, businessStatus, businessReason, checkedAt, baselineLabel, comparedRows,
                null, null, null, null);
    }
}
