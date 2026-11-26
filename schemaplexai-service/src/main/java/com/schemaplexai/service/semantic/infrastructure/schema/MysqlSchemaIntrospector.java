package com.schemaplexai.service.semantic.infrastructure.schema;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;

/** MySQL information_schema 扫描适配器。 */
@Component
public class MysqlSchemaIntrospector extends AbstractRelationalSchemaIntrospector {

    private static final String COLUMNS = """
            SELECT c.table_schema AS object_schema, c.table_name AS object_name,
                   t.table_type AS object_type, t.table_comment AS object_comment,
                   c.column_name AS field_name, c.column_type AS data_type,
                   (c.is_nullable = 'YES') AS nullable, c.column_comment AS field_comment
            FROM information_schema.columns c
            JOIN information_schema.tables t
              ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            WHERE c.table_schema NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')%s
            ORDER BY c.table_schema, c.table_name, c.ordinal_position
            """;

    private static final String CONSTRAINTS = """
            SELECT tc.table_schema AS object_schema, tc.table_name AS object_name,
                   tc.constraint_name, tc.constraint_type, kcu.column_name AS field_name,
                   kcu.referenced_table_schema AS referenced_schema,
                   kcu.referenced_table_name AS referenced_object,
                   kcu.referenced_column_name AS referenced_field
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_schema = tc.constraint_schema AND kcu.constraint_name = tc.constraint_name
             AND kcu.table_name = tc.table_name
            WHERE tc.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY')%s
            ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position
            """;

    private static final String INDEXES = """
            SELECT table_schema AS object_schema, table_name AS object_name,
                   index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns,
                   (non_unique = 0) AS is_unique, index_type
            FROM information_schema.statistics
            WHERE table_schema NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')%s
            GROUP BY table_schema, table_name, index_name, non_unique, index_type
            ORDER BY table_schema, table_name, index_name
            """;

    @Override
    public boolean supports(String databaseType) {
        return "mysql".equalsIgnoreCase(databaseType) || "mariadb".equalsIgnoreCase(databaseType);
    }

    @Override
    protected String databaseType() {
        return "mysql";
    }

    @Override
    protected String columnsQuery(String schema, Set<String> objects) {
        return COLUMNS.formatted(scopePredicate("c.", schema, objects));
    }

    @Override
    protected String constraintsQuery(String schema, Set<String> objects) {
        return CONSTRAINTS.formatted(scopePredicate("tc.", schema, objects));
    }

    @Override
    protected String indexesQuery(String schema, Set<String> objects) {
        return INDEXES.formatted(scopePredicate("", schema, objects));
    }

    private String scopePredicate(String qualifier, String schema, Set<String> objects) {
        StringBuilder sql = new StringBuilder();
        if (schema != null) {
            sql.append(" AND ").append(qualifier).append("table_schema = ").append(mysqlString(schema));
        }
        if (!objects.isEmpty()) {
            sql.append(" AND ").append(qualifier).append("table_name IN (")
                    .append(objects.stream().sorted().map(this::mysqlString).collect(Collectors.joining(", ")))
                    .append(')');
        }
        return sql.toString();
    }

    private String mysqlString(String value) {
        String encoded = HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
        return "CONVERT(X'" + encoded + "' USING utf8mb4)";
    }
}
