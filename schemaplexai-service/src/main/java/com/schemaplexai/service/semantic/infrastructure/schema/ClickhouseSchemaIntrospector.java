package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import com.schemaplexai.service.semantic.domain.port.SchemaIntrospectorPort;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSession;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** ClickHouse system 表扫描适配器。 */
@Component
public class ClickhouseSchemaIntrospector implements SchemaIntrospectorPort {

    private static final String STRUCTURE_QUERY = """
            SELECT c.database AS object_schema, c.table AS object_name,
                   c.name AS field_name, c.type AS data_type, c.comment AS field_comment,
                   t.engine, t.partition_key, t.primary_key, t.comment AS object_comment
            FROM system.columns c
            JOIN system.tables t ON t.database = c.database AND t.name = c.table
            WHERE c.database NOT IN ('system', 'information_schema', 'INFORMATION_SCHEMA')
            ORDER BY c.database, c.table, c.position
            """;

    @Override
    public boolean supports(String databaseType) {
        return "clickhouse".equalsIgnoreCase(databaseType);
    }

    @Override
    public DatabaseSchema scan(SchemaScanScope scope, SchemaMetadataSession session) {
        String database = scope.schema() == null || scope.schema().isBlank()
                ? session.defaultSchema()
                : scope.schema().trim();
        Map<String, ObjectSeed> seeds = new LinkedHashMap<>();
        for (Map<String, Object> row : session.query(STRUCTURE_QUERY)) {
            String rowDatabase = SchemaRows.text(row, "object_schema", "database");
            String table = SchemaRows.text(row, "object_name", "table");
            if (table == null || database != null && !database.equals(rowDatabase)
                    || !SchemaRows.included(table, scope.includeObjects())) {
                continue;
            }
            String key = (rowDatabase == null ? "" : rowDatabase) + "." + table;
            ObjectSeed seed = seeds.computeIfAbsent(key, ignored -> new ObjectSeed(
                    rowDatabase,
                    table,
                    SchemaRows.text(row, "object_comment"),
                    SchemaRows.text(row, "engine"),
                    SchemaRows.text(row, "partition_key"),
                    SchemaRows.strings(row, "primary_key")));
            String field = SchemaRows.text(row, "field_name", "name");
            String type = SchemaRows.text(row, "data_type", "type");
            if (field != null && type != null) {
                seed.fields.add(new SchemaField(field, type, type.startsWith("Nullable("),
                        SchemaRows.text(row, "field_comment", "comment"), Map.of()));
            }
        }
        return new DatabaseSchema("clickhouse", scope.catalog(), database,
                seeds.values().stream().map(ObjectSeed::toObject).toList(),
                Set.of("tables", "columns", "comments", "engines", "partition_keys", "primary_keys"),
                List.of());
    }

    private static final class ObjectSeed {
        private final String namespace;
        private final String name;
        private final String comment;
        private final String engine;
        private final String partitionKey;
        private final List<String> primaryKey;
        private final List<SchemaField> fields = new ArrayList<>();

        private ObjectSeed(
                String namespace,
                String name,
                String comment,
                String engine,
                String partitionKey,
                List<String> primaryKey) {
            this.namespace = namespace;
            this.name = name;
            this.comment = comment;
            this.engine = engine;
            this.partitionKey = partitionKey;
            this.primaryKey = primaryKey;
        }

        private SchemaObject toObject() {
            return new SchemaObject(namespace, name, SchemaObjectType.TABLE, comment, engine, partitionKey,
                    fields, primaryKey, List.of(), List.of());
        }
    }
}
