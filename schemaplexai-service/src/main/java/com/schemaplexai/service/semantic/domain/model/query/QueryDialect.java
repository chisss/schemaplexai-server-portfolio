package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Locale;

/** 单源查询编译方言。 */
public enum QueryDialect {
    POSTGRESQL,
    MYSQL,
    CLICKHOUSE,
    MONGODB;

    public static QueryDialect fromDatabaseType(String databaseType) {
        if (databaseType == null || databaseType.isBlank()) {
            throw new IllegalArgumentException("databaseType is required");
        }
        return switch (databaseType.trim().toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql" -> POSTGRESQL;
            case "mysql", "mariadb" -> MYSQL;
            case "clickhouse" -> CLICKHOUSE;
            case "mongo", "mongodb" -> MONGODB;
            default -> throw new IllegalArgumentException("unsupported database type: " + databaseType);
        };
    }

    public String databaseType() {
        return switch (this) {
            case POSTGRESQL -> "postgresql";
            case MYSQL -> "mysql";
            case CLICKHOUSE -> "clickhouse";
            case MONGODB -> "mongodb";
        };
    }
}
