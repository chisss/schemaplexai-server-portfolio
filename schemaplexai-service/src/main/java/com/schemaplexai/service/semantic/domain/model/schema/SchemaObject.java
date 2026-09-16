package com.schemaplexai.service.semantic.domain.model.schema;

import java.util.List;
import java.util.Objects;

/** 表、视图或集合的结构。 */
public record SchemaObject(
        String namespace,
        String name,
        SchemaObjectType type,
        String comment,
        String engine,
        String partitionKey,
        List<SchemaField> fields,
        List<String> primaryKey,
        List<SchemaForeignKey> foreignKeys,
        List<SchemaIndex> indexes) implements Comparable<SchemaObject> {

    public SchemaObject {
        namespace = normalize(namespace);
        name = Objects.requireNonNull(normalize(name), "object name is required");
        type = Objects.requireNonNull(type, "object type is required");
        comment = normalize(comment);
        engine = normalize(engine);
        partitionKey = normalize(partitionKey);
        fields = sorted(fields);
        primaryKey = primaryKey == null ? List.of() : primaryKey.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        foreignKeys = sorted(foreignKeys);
        indexes = sorted(indexes);
    }

    public SchemaObject(
            String name,
            SchemaObjectType type,
            String comment,
            String engine,
            String partitionKey,
            List<SchemaField> fields,
            List<String> primaryKey,
            List<SchemaForeignKey> foreignKeys,
            List<SchemaIndex> indexes) {
        this(null, name, type, comment, engine, partitionKey, fields, primaryKey, foreignKeys, indexes);
    }

    @Override
    public int compareTo(SchemaObject other) {
        int namespaceOrder = java.util.Comparator.nullsFirst(String::compareTo)
                .compare(namespace, other.namespace);
        return namespaceOrder == 0 ? name.compareTo(other.name) : namespaceOrder;
    }

    private static <T extends Comparable<? super T>> List<T> sorted(List<T> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
