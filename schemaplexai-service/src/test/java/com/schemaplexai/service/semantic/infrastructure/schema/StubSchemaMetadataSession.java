package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSession;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

final class StubSchemaMetadataSession implements SchemaMetadataSession {

    private final String databaseType;
    private final String defaultSchema;
    private final Function<String, List<Map<String, Object>>> queryHandler;
    private final BiFunction<String, Map<String, Object>, List<Map<String, Object>>> invokeHandler;

    StubSchemaMetadataSession(
            String databaseType,
            String defaultSchema,
            Function<String, List<Map<String, Object>>> queryHandler,
            BiFunction<String, Map<String, Object>, List<Map<String, Object>>> invokeHandler) {
        this.databaseType = databaseType;
        this.defaultSchema = defaultSchema;
        this.queryHandler = queryHandler;
        this.invokeHandler = invokeHandler;
    }

    @Override
    public String sourceId() { return "source-1"; }

    @Override
    public String databaseType() { return databaseType; }

    @Override
    public String defaultCatalog() { return "catalog-1"; }

    @Override
    public String defaultSchema() { return defaultSchema; }

    @Override
    public List<Map<String, Object>> query(String statement) {
        return queryHandler.apply(statement);
    }

    @Override
    public List<Map<String, Object>> invoke(String operation, Map<String, Object> arguments) {
        return invokeHandler.apply(operation, arguments);
    }
}
