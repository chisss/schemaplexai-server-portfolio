package com.schemaplexai.service.semantic.application.command;

import java.util.Set;

/** 发起 Schema 扫描的结构化参数。 */
public record ScanSchemaCommand(String catalog, String schema, Set<String> includeObjects) {

    public ScanSchemaCommand {
        includeObjects = includeObjects == null ? Set.of() : Set.copyOf(includeObjects);
    }
}
