package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.database.security.SqlReadOnlyGuard;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MysqlSchemaIntrospectorTest {

    @Test
    void scopesEveryMysqlMetadataStatementUsingUtf8Literals() {
        var statements = new ArrayList<String>();
        var guard = new SqlReadOnlyGuard();
        var session = new StubSchemaMetadataSession("mysql", "default_db", statement -> {
            guard.validate(statement);
            statements.add(statement);
            return List.of();
        }, (operation, arguments) -> List.of());
        var scope = new SchemaScanScope(null, "sales", Set.of("orders", "region'\\map"));

        var schema = new MysqlSchemaIntrospector().scan(scope, session);

        assertThat(schema.schema()).isEqualTo("sales");
        assertThat(statements).hasSize(3).allSatisfy(sql -> {
            assertThat(sql).contains("table_schema = CONVERT(X'73616c6573' USING utf8mb4)")
                    .contains("table_name IN (CONVERT(X'6f7264657273' USING utf8mb4), "
                            + "CONVERT(X'726567696f6e275c6d6170' USING utf8mb4))")
                    .doesNotContain("default_db", "region'");
        });
    }

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
