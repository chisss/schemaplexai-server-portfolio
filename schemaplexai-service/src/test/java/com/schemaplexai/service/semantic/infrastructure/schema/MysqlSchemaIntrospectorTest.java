package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlSchemaIntrospectorTest {

    @Test
    void readsMysqlColumnMetadataAndNullability() {
        StubSchemaMetadataSession session = new StubSchemaMetadataSession(
                "mysql", "sales",
                statement -> statement.contains("information_schema.columns")
                        ? List.of(Map.of(
                                "object_schema", "sales", "object_name", "orders",
                                "object_type", "BASE TABLE", "field_name", "note",
                                "data_type", "varchar(255)", "nullable", true,
                                "field_comment", "备注"))
                        : List.of(),
                (operation, arguments) -> List.of());

        var schema = new MysqlSchemaIntrospector().scan(SchemaScanScope.all(), session);

        assertThat(schema.databaseType()).isEqualTo("mysql");
        assertThat(schema.schema()).isEqualTo("sales");
        assertThat(schema.objects().getFirst().fields().getFirst()).satisfies(field -> {
            assertThat(field.name()).isEqualTo("note");
            assertThat(field.nullable()).isTrue();
            assertThat(field.comment()).isEqualTo("备注");
        });
    }
}
