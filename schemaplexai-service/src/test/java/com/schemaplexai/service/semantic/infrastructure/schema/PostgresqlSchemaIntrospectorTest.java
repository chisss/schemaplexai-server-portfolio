package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresqlSchemaIntrospectorTest {

    @Test
    void assemblesColumnsKeysAndIndexesWithinRequestedSchema() {
        StubSchemaMetadataSession session = new StubSchemaMetadataSession(
                "postgresql", "public", this::rowsFor, (operation, arguments) -> List.of());

        var schema = new PostgresqlSchemaIntrospector().scan(
                new SchemaScanScope(null, "public", Set.of("orders")), session);

        assertThat(schema.objects()).hasSize(1);
        var orders = schema.objects().getFirst();
        assertThat(orders.namespace()).isEqualTo("public");
        assertThat(orders.name()).isEqualTo("orders");
        assertThat(orders.fields()).extracting("name").containsExactly("customer_id", "customer_region", "id");
        assertThat(orders.primaryKey()).containsExactly("id");
        assertThat(orders.foreignKeys()).singleElement().satisfies(foreignKey -> {
            assertThat(foreignKey.columns()).containsExactly("customer_id", "customer_region");
            assertThat(foreignKey.referencedObject()).isEqualTo("customers");
            assertThat(foreignKey.referencedColumns()).containsExactly("id", "region");
        });
        assertThat(orders.indexes()).extracting("name").containsExactly("idx_orders_customer");
    }

    @Test
    void preservesNamespaceForSameNamedObjectsAcrossSchemas() {
        StubSchemaMetadataSession session = new StubSchemaMetadataSession(
                "postgresql", null, statement -> statement.contains("information_schema.columns")
                        ? List.of(row("sales", "orders", "id", "uuid"),
                                row("archive", "orders", "id", "uuid"))
                        : List.of(),
                (operation, arguments) -> List.of());

        var schema = new PostgresqlSchemaIntrospector().scan(SchemaScanScope.all(), session);

        assertThat(schema.objects()).extracting("namespace")
                .containsExactly("archive", "sales");
        assertThat(schema.objects()).extracting("name")
                .containsExactly("orders", "orders");
    }

    private List<Map<String, Object>> rowsFor(String statement) {
        if (statement.contains("information_schema.columns")) {
            return List.of(
                    row("public", "orders", "customer_id", "uuid"),
                    row("public", "orders", "customer_region", "varchar"),
                    row("audit", "orders", "id", "uuid"),
                    row("public", "orders", "id", "uuid"));
        }
        if (statement.contains("table_constraints")) {
            assertThat(statement).contains("kcu.position_in_unique_constraint");
            return List.of(
                    Map.of("object_schema", "public", "object_name", "orders",
                            "constraint_name", "orders_pkey", "constraint_type", "PRIMARY KEY", "field_name", "id"),
                    Map.of("object_schema", "public", "object_name", "orders",
                            "constraint_name", "orders_customer_fk", "constraint_type", "FOREIGN KEY",
                            "field_name", "customer_id", "referenced_schema", "public",
                            "referenced_object", "customers", "referenced_field", "id"),
                    Map.of("object_schema", "public", "object_name", "orders",
                            "constraint_name", "orders_customer_fk", "constraint_type", "FOREIGN KEY",
                            "field_name", "customer_region", "referenced_schema", "public",
                            "referenced_object", "customers", "referenced_field", "region"));
        }
        return List.of(Map.of(
                "object_schema", "public", "object_name", "orders",
                "index_name", "idx_orders_customer", "columns", List.of("customer_id"),
                "is_unique", false, "index_type", "btree"));
    }

    private Map<String, Object> row(String schema, String table, String column, String type) {
        return Map.of(
                "object_schema", schema,
                "object_name", table,
                "object_type", "BASE TABLE",
                "field_name", column,
                "data_type", type,
                "nullable", false);
    }
}
