package com.schemaplexai.service.semantic.domain.model.query;

import java.time.LocalDate;
import java.util.Objects;

/** 查询时间范围，支持相对天数和已解析的日期边界。 */
public record QueryTimeRange(
        String fieldIri,
        Integer relativeDays,
        LocalDate from,
        LocalDate to) {

    public QueryTimeRange {
        fieldIri = Objects.requireNonNull(fieldIri, "fieldIri is required").trim();
        if (relativeDays != null && relativeDays < 1) {
            throw new IllegalArgumentException("relativeDays must be positive");
        }
        if ((from == null) != (to == null)) {
            throw new IllegalArgumentException("from and to must be provided together");
        }
        if (from != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from must not be after to");
        }
    }
}
