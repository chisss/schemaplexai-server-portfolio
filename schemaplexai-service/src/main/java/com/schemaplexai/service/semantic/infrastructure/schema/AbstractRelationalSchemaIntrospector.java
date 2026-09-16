package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaForeignKey;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaIndex;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import com.schemaplexai.service.semantic.domain.port.SchemaIntrospectorPort;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** PostgreSQL 与 MySQL 共享的关系型结构组装逻辑。 */
abstract class AbstractRelationalSchemaIntrospector implements SchemaIntrospectorPort {

    @Override
    public DatabaseSchema scan(SchemaScanScope scope, SchemaMetadataSession session) {
        String catalog = firstText(scope.catalog(), session.defaultCatalog());
        String schema = firstText(scope.schema(), session.defaultSchema());
        List<Map<String, Object>> columnRows = session.query(columnsQuery());
        List<Map<String, Object>> constraintRows = session.query(constraintsQuery());
        List<Map<String, Object>> indexRows = session.query(indexesQuery());

        Map<String, ObjectSeed> seeds = new LinkedHashMap<>();
        for (Map<String, Object> row : columnRows) {
            String objectSchema = SchemaRows.text(row, "object_schema", "table_schema");
            String objectName = SchemaRows.text(row, "object_name", "table_name");
            if (objectName == null || !matches(schema, objectSchema)
                    || !SchemaRows.included(objectName, scope.includeObjects())) {
                continue;
            }
            String key = key(objectSchema, objectName);
            ObjectSeed seed = seeds.computeIfAbsent(key, ignored -> new ObjectSeed(
                    objectSchema,
                    objectName,
                    resolveObjectType(SchemaRows.text(row, "object_type", "table_type")),
                    SchemaRows.text(row, "object_comment", "table_comment")));
            String fieldName = SchemaRows.text(row, "field_name", "column_name");
            String dataType = SchemaRows.text(row, "data_type", "column_type", "udt_name");
            if (fieldName != null && dataType != null) {
                seed.fields.add(new SchemaField(
                        fieldName,
                        dataType,
                        SchemaRows.bool(row, "nullable", "is_nullable"),
                        SchemaRows.text(row, "field_comment", "column_comment"),
                        Map.of()));
            }
        }

        List<SchemaObject> objects = new ArrayList<>();
        for (ObjectSeed seed : seeds.values()) {
            List<Map<String, Object>> objectConstraints = SchemaRows.matching(
                    constraintRows, seed.schema, seed.name);
            List<Map<String, Object>> objectIndexes = SchemaRows.matching(indexRows, seed.schema, seed.name);
            objects.add(seed.toObject(objectConstraints, objectIndexes));
        }
        return new DatabaseSchema(databaseType(), catalog, schema, objects, capabilities(), List.of());
    }

    protected abstract String databaseType();

    protected abstract String columnsQuery();

    protected abstract String constraintsQuery();

    protected abstract String indexesQuery();

    protected Set<String> capabilities() {
        return Set.of("tables", "views", "columns", "primary_keys", "foreign_keys", "indexes", "comments");
    }

    private boolean matches(String expectedSchema, String actualSchema) {
        return expectedSchema == null || expectedSchema.equals(actualSchema);
    }

    private String key(String schema, String object) {
        return (schema == null ? "" : schema) + "." + object;
    }

    private String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred.trim();
    }

    private SchemaObjectType resolveObjectType(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("view")
                ? SchemaObjectType.VIEW
                : SchemaObjectType.TABLE;
    }

    private static final class ObjectSeed {
        private final String schema;
        private final String name;
        private final SchemaObjectType type;
        private final String comment;
        private final List<SchemaField> fields = new ArrayList<>();

        private ObjectSeed(String schema, String name, SchemaObjectType type, String comment) {
            this.schema = schema;
            this.name = name;
            this.type = type;
            this.comment = comment;
        }

        private SchemaObject toObject(
                List<Map<String, Object>> constraints,
                List<Map<String, Object>> indexes) {
            List<String> primaryKey = constraints.stream()
                    .filter(row -> "PRIMARY KEY".equalsIgnoreCase(SchemaRows.text(row, "constraint_type")))
                    .map(row -> SchemaRows.text(row, "field_name", "column_name"))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, ForeignKeySeed> foreignKeySeeds = new LinkedHashMap<>();
            constraints.stream()
                    .filter(row -> "FOREIGN KEY".equalsIgnoreCase(SchemaRows.text(row, "constraint_type")))
                    .forEach(row -> {
                        String name = SchemaRows.text(row, "constraint_name");
                        ForeignKeySeed seed = foreignKeySeeds.computeIfAbsent(
                                name == null ? "" : name,
                                ignored -> new ForeignKeySeed(
                                        name,
                                        SchemaRows.text(row, "referenced_schema"),
                                        SchemaRows.text(row, "referenced_object", "referenced_table")));
                        seed.columns.add(SchemaRows.text(row, "field_name", "column_name"));
                        seed.referencedColumns.add(SchemaRows.text(row, "referenced_field", "referenced_column"));
                    });
            List<SchemaForeignKey> foreignKeys = foreignKeySeeds.values().stream()
                    .map(ForeignKeySeed::toForeignKey)
                    .toList();
            List<SchemaIndex> schemaIndexes = indexes.stream()
                    .map(row -> new SchemaIndex(
                            SchemaRows.text(row, "index_name", "name"),
                            SchemaRows.strings(row, "columns", "column_names"),
                            SchemaRows.bool(row, "is_unique", "unique"),
                            SchemaRows.text(row, "index_type", "type")))
                    .toList();
            return new SchemaObject(schema, name, type, comment, null, null, fields,
                    primaryKey, foreignKeys, schemaIndexes);
        }
    }

    private static final class ForeignKeySeed {
        private final String name;
        private final String referencedSchema;
        private final String referencedObject;
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();

        private ForeignKeySeed(String name, String referencedSchema, String referencedObject) {
            this.name = name;
            this.referencedSchema = referencedSchema;
            this.referencedObject = referencedObject;
        }

        private SchemaForeignKey toForeignKey() {
            return new SchemaForeignKey(name, columns.stream().filter(java.util.Objects::nonNull).toList(),
                    referencedSchema, referencedObject,
                    referencedColumns.stream().filter(java.util.Objects::nonNull).toList());
        }
    }
}
