package com.schemaplexai.service.semantic.domain.model.schema;

import java.util.List;

/** 外键结构。 */
public record SchemaForeignKey(
        String name,
        List<String> columns,
        String referencedSchema,
        String referencedObject,
        List<String> referencedColumns) implements Comparable<SchemaForeignKey> {

    public SchemaForeignKey {
        name = normalize(name);
        columns = normalized(columns);
        referencedSchema = normalize(referencedSchema);
        referencedObject = normalize(referencedObject);
        referencedColumns = normalized(referencedColumns);
    }

    @Override
    public int compareTo(SchemaForeignKey other) {
        return key().compareTo(other.key());
    }

    private String key() {
        return (name == null ? "" : name) + "|" + String.join(",", columns);
    }

    private static List<String> normalized(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
