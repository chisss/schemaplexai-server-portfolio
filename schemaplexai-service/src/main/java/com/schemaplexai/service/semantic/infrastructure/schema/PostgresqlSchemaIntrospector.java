package com.schemaplexai.service.semantic.infrastructure.schema;

import org.springframework.stereotype.Component;

/** PostgreSQL information_schema 扫描适配器。 */
@Component
public class PostgresqlSchemaIntrospector extends AbstractRelationalSchemaIntrospector {

    private static final String COLUMNS = """
            SELECT c.table_schema AS object_schema, c.table_name AS object_name,
                   t.table_type AS object_type, obj_description(pc.oid) AS object_comment,
                   c.column_name AS field_name, c.data_type, c.udt_name,
                   (c.is_nullable = 'YES') AS nullable,
                   col_description(pc.oid, c.ordinal_position) AS field_comment
            FROM information_schema.columns c
            JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
            LEFT JOIN pg_catalog.pg_namespace pn ON pn.nspname = c.table_schema
            LEFT JOIN pg_catalog.pg_class pc ON pc.relname = c.table_name AND pc.relnamespace = pn.oid
            WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
            ORDER BY c.table_schema, c.table_name, c.ordinal_position
            """;

    private static final String CONSTRAINTS = """
            SELECT tc.table_schema AS object_schema, tc.table_name AS object_name,
                   tc.constraint_name, tc.constraint_type, kcu.column_name AS field_name,
                   referenced.table_schema AS referenced_schema,
                   referenced.table_name AS referenced_object,
                   referenced.column_name AS referenced_field
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
              ON kcu.constraint_schema = tc.constraint_schema AND kcu.constraint_name = tc.constraint_name
            LEFT JOIN information_schema.referential_constraints rc
              ON rc.constraint_schema = tc.constraint_schema AND rc.constraint_name = tc.constraint_name
            LEFT JOIN information_schema.key_column_usage referenced
              ON referenced.constraint_schema = rc.unique_constraint_schema
             AND referenced.constraint_name = rc.unique_constraint_name
             AND referenced.ordinal_position = kcu.position_in_unique_constraint
            WHERE tc.constraint_type IN ('PRIMARY KEY', 'FOREIGN KEY')
            ORDER BY tc.table_schema, tc.table_name, tc.constraint_name, kcu.ordinal_position
            """;

    private static final String INDEXES = """
            SELECT ns.nspname AS object_schema, tbl.relname AS object_name,
                   idx.relname AS index_name,
                   array_agg(att.attname ORDER BY ord.ordinality) AS columns,
                   ind.indisunique AS is_unique, am.amname AS index_type
            FROM pg_catalog.pg_index ind
            JOIN pg_catalog.pg_class idx ON idx.oid = ind.indexrelid
            JOIN pg_catalog.pg_class tbl ON tbl.oid = ind.indrelid
            JOIN pg_catalog.pg_namespace ns ON ns.oid = tbl.relnamespace
            JOIN pg_catalog.pg_am am ON am.oid = idx.relam
            JOIN LATERAL unnest(ind.indkey) WITH ORDINALITY ord(attnum, ordinality) ON true
            JOIN pg_catalog.pg_attribute att ON att.attrelid = tbl.oid AND att.attnum = ord.attnum
            WHERE ns.nspname NOT IN ('pg_catalog', 'information_schema')
            GROUP BY ns.nspname, tbl.relname, idx.relname, ind.indisunique, am.amname
            ORDER BY ns.nspname, tbl.relname, idx.relname
            """;

    @Override
    public boolean supports(String databaseType) {
        return "postgresql".equalsIgnoreCase(databaseType) || "postgres".equalsIgnoreCase(databaseType);
    }

    @Override
    protected String databaseType() {
        return "postgresql";
    }

    @Override
    protected String columnsQuery() { return COLUMNS; }

    @Override
    protected String constraintsQuery() { return CONSTRAINTS; }

    @Override
    protected String indexesQuery() { return INDEXES; }
}
