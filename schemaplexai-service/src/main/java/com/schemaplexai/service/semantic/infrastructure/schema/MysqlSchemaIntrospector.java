package com.schemaplexai.service.semantic.infrastructure.schema;

import org.springframework.stereotype.Component;

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
            WHERE c.table_schema NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
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
            WHERE tc.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY')
            ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position
            """;

    private static final String INDEXES = """
            SELECT table_schema AS object_schema, table_name AS object_name,
                   index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns,
                   (non_unique = 0) AS is_unique, index_type
            FROM information_schema.statistics
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
    protected String columnsQuery() { return COLUMNS; }

    @Override
    protected String constraintsQuery() { return CONSTRAINTS; }

    @Override
    protected String indexesQuery() { return INDEXES; }
}
