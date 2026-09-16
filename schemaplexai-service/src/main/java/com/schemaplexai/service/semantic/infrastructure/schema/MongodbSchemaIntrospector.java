package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaIndex;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import com.schemaplexai.service.semantic.domain.port.SchemaIntrospectorPort;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSession;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** MongoDB 集合、字段类型分布与索引扫描适配器。 */
@Component
public class MongodbSchemaIntrospector implements SchemaIntrospectorPort {

    @Override
    public boolean supports(String databaseType) {
        return "mongodb".equalsIgnoreCase(databaseType) || "mongo".equalsIgnoreCase(databaseType);
    }

    @Override
    public DatabaseSchema scan(SchemaScanScope scope, SchemaMetadataSession session) {
        String database = scope.schema() == null || scope.schema().isBlank()
                ? session.defaultSchema()
                : scope.schema().trim();
        Map<String, Object> databaseArgument = database == null ? Map.of() : Map.of("database", database);
        List<Map<String, Object>> collections = session.invoke("list_collections", databaseArgument);
        List<SchemaObject> objects = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (Map<String, Object> collectionRow : collections) {
            String collection = SchemaRows.text(collectionRow, "collection_name", "name", "collection");
            if (collection == null || !SchemaRows.included(collection, scope.includeObjects())) {
                continue;
            }
            Map<String, Object> collectionArguments = database == null
                    ? Map.of("collection", collection)
                    : Map.of("database", database, "collection", collection);
            Map<String, Object> describeArguments = new java.util.LinkedHashMap<>(collectionArguments);
            describeArguments.put("sampleLimit", 100);
            List<Map<String, Object>> fieldRows = session.invoke("describe_collection", describeArguments);
            List<Map<String, Object>> indexRows = session.invoke("list_indexes", collectionArguments);
            List<SchemaField> fields = fieldRows.stream()
                    .map(this::toField)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            if (fields.isEmpty()) {
                warnings.add("集合 " + collection + " 未返回字段结构");
            }
            List<SchemaIndex> indexes = indexRows.stream()
                    .map(this::toIndex)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            objects.add(new SchemaObject(database, collection, SchemaObjectType.COLLECTION,
                    SchemaRows.text(collectionRow, "comment", "description"),
                    null, null, fields, List.of(), List.of(), indexes));
        }
        return new DatabaseSchema("mongodb", scope.catalog(), database, objects,
                Set.of("collections", "field_type_distribution", "indexes", "limited_sampling"), warnings);
    }

    private SchemaField toField(Map<String, Object> row) {
        String name = SchemaRows.text(row, "field_name", "path", "name");
        String type = SchemaRows.text(row, "data_type", "dominant_type", "type");
        if (name == null || type == null) {
            return null;
        }
        return new SchemaField(name, type, SchemaRows.bool(row, "nullable", "optional"),
                SchemaRows.text(row, "comment", "description"), SchemaRows.distribution(row));
    }

    private SchemaIndex toIndex(Map<String, Object> row) {
        String name = SchemaRows.text(row, "index_name", "name");
        if (name == null) {
            return null;
        }
        return new SchemaIndex(name, SchemaRows.strings(row, "fields", "keys", "columns"),
                SchemaRows.bool(row, "unique", "is_unique"),
                SchemaRows.text(row, "index_type", "type"));
    }
}
