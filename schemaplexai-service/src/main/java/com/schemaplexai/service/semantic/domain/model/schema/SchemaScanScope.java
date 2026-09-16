package com.schemaplexai.service.semantic.domain.model.schema;

import java.util.Set;

/** 数据库结构扫描的领域范围。 */
public record SchemaScanScope(String catalog, String schema, Set<String> includeObjects) {

    public SchemaScanScope {
        includeObjects = includeObjects == null ? Set.of() : Set.copyOf(includeObjects);
    }

    public static SchemaScanScope all() {
        return new SchemaScanScope(null, null, Set.of());
    }
}
