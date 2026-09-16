package com.schemaplexai.service.semantic.domain.model.schema;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 表字段或文档字段，仅包含结构信息。 */
public record SchemaField(
        String name,
        String dataType,
        boolean nullable,
        String comment,
        Map<String, Long> typeDistribution) implements Comparable<SchemaField> {

    public SchemaField {
        name = requireText(name, "field name");
        dataType = requireText(dataType, "field dataType");
        comment = normalize(comment);
        typeDistribution = typeDistribution == null
                ? Map.of()
                : Map.copyOf(new TreeMap<>(typeDistribution));
    }

    @Override
    public int compareTo(SchemaField other) {
        return name.compareTo(other.name);
    }

    private static String requireText(String value, String field) {
        String normalized = normalize(value);
        return Objects.requireNonNull(normalized, field + " is required");
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
