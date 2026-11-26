package com.schemaplexai.model.vo.semantic;

import java.util.List;

/** 工作台浏览的库、表、列和键。 */
public record SchemaSnapshotDetailVO(String id, String sourceId, String status, String databaseType, Schema schema) {

    public record Schema(String catalog, String schema, List<ObjectSchema> objects) {
    }

    public record ObjectSchema(
            String key, String namespace, String name, String type,
            List<Field> fields, List<String> primaryKey, List<ForeignKey> foreignKeys) {
    }

    public record Field(String name, String dataType, boolean nullable) {
    }

    public record ForeignKey(
            String name, List<String> columns, String referencedSchema,
            String referencedObject, List<String> referencedColumns) {
    }
}
