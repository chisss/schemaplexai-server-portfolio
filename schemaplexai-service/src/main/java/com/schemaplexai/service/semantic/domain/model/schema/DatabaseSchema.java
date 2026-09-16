package com.schemaplexai.service.semantic.domain.model.schema;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 单次扫描得到的规范化数据库结构。 */
public record DatabaseSchema(
        String databaseType,
        String catalog,
        String schema,
        List<SchemaObject> objects,
        Set<String> capabilities,
        List<String> warnings) {

    public DatabaseSchema {
        databaseType = normalize(databaseType);
        catalog = normalize(catalog);
        schema = normalize(schema);
        objects = objects == null ? List.of() : objects.stream().sorted().toList();
        capabilities = capabilities == null
                ? Set.of()
                : Set.copyOf(new TreeSet<>(capabilities));
        warnings = warnings == null ? List.of() : warnings.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .sorted()
                .toList();
    }

    public boolean partial() {
        return !warnings.isEmpty();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
