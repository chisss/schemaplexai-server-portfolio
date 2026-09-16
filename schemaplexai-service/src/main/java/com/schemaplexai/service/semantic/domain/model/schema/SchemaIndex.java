package com.schemaplexai.service.semantic.domain.model.schema;

import java.util.List;
import java.util.Objects;

/** 索引结构。 */
public record SchemaIndex(
        String name,
        List<String> columns,
        boolean unique,
        String type) implements Comparable<SchemaIndex> {

    public SchemaIndex {
        name = Objects.requireNonNull(normalize(name), "index name is required");
        columns = columns == null ? List.of() : columns.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        type = normalize(type);
    }

    @Override
    public int compareTo(SchemaIndex other) {
        return name.compareTo(other.name);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
