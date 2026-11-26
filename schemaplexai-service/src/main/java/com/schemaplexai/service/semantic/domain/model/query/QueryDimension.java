package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Objects;

/** 查询维度。 */
public record QueryDimension(
        String iri,
        String alias,
        String timeGrain,
        String physicalObject,
        String physicalField,
        QueryCalendar calendar) {

    public QueryDimension(String iri, String alias, String timeGrain, String physicalObject, String physicalField) {
        this(iri, alias, timeGrain, physicalObject, physicalField, null);
    }

    public QueryDimension {
        iri = requireText(iri, "iri");
        alias = requireText(alias, "alias");
        timeGrain = normalize(timeGrain);
        physicalObject = requireText(physicalObject, "physicalObject");
        physicalField = requireText(physicalField, "physicalField");
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
