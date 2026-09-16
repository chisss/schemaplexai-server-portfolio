package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaFingerprintTest {

    private final SchemaFingerprint fingerprint = new SchemaFingerprint();

    @Test
    void ignoresDatabaseReturnOrderAndDiagnosticWarnings() {
        SchemaObject orders = object("orders", List.of(field("id", "bigint"), field("amount", "decimal")));
        SchemaObject customers = object("customers", List.of(field("name", "varchar"), field("id", "bigint")));

        DatabaseSchema left = new DatabaseSchema(
                "postgresql", "sales", "public", List.of(orders, customers), Set.of("tables"), List.of());
        DatabaseSchema right = new DatabaseSchema(
                "postgresql", "sales", "public", List.of(customers, orders), Set.of("tables"), List.of("partial"));

        assertThat(fingerprint.calculate(left)).isEqualTo(fingerprint.calculate(right));
    }

    @Test
    void changesWhenAFieldTypeChanges() {
        DatabaseSchema left = schema(field("amount", "decimal"));
        DatabaseSchema right = schema(field("amount", "varchar"));

        assertThat(fingerprint.calculate(left)).isNotEqualTo(fingerprint.calculate(right));
    }

    @Test
    void changesWhenObjectNamespaceChanges() {
        SchemaObject sales = new SchemaObject("sales", "orders", SchemaObjectType.TABLE,
                null, null, null, List.of(field("id", "uuid")), List.of(), List.of(), List.of());
        SchemaObject archive = new SchemaObject("archive", "orders", SchemaObjectType.TABLE,
                null, null, null, List.of(field("id", "uuid")), List.of(), List.of(), List.of());

        DatabaseSchema left = new DatabaseSchema(
                "postgresql", null, null, List.of(sales), Set.of(), List.of());
        DatabaseSchema right = new DatabaseSchema(
                "postgresql", null, null, List.of(archive), Set.of(), List.of());

        assertThat(fingerprint.calculate(left)).isNotEqualTo(fingerprint.calculate(right));
    }

    private DatabaseSchema schema(SchemaField field) {
        return new DatabaseSchema("mysql", null, "sales", List.of(object("orders", List.of(field))), Set.of(), List.of());
    }

    private SchemaObject object(String name, List<SchemaField> fields) {
        return new SchemaObject(name, SchemaObjectType.TABLE, null, null, null,
                fields, List.of(), List.of(), List.of());
    }

    private SchemaField field(String name, String type) {
        return new SchemaField(name, type, false, null, Map.of());
    }
}
