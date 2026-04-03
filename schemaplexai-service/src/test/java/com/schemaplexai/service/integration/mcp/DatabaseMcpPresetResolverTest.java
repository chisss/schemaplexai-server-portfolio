package com.schemaplexai.service.integration.mcp;

import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.model.entity.McpServer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseMcpPresetResolverTest {

    private final DatabaseMcpPresetResolver resolver = new DatabaseMcpPresetResolver();

    @Test
    void shouldResolvePostgresqlCommandFromConnectionParts() {
        McpServer server = new McpServer();
        server.setServerType(McpServerTypeEnum.DATABASE.getCode());
        server.setPresetCode("postgresql");
        server.setConnectionConfig(Map.of(
                "host", "db.internal",
                "port", "5432",
                "database", "schemaplexai",
                "username", "readonly",
                "password", "secret"
        ));

        List<String> command = resolver.resolveCommand(server);

        assertThat(command).containsExactly(
                "npx",
                "-y",
                "@modelcontextprotocol/server-postgres",
                "postgresql://readonly:secret@db.internal:5432/schemaplexai"
        );
    }

    @Test
    void shouldResolveMysqlEnvironmentAndReadOnlyFlags() {
        McpServer server = new McpServer();
        server.setServerType(McpServerTypeEnum.DATABASE.getCode());
        server.setPresetCode("mysql");
        server.setConnectionConfig(Map.of(
                "host", "mysql.internal",
                "port", "3306",
                "database", "app",
                "username", "readonly",
                "password", "mysql-pass",
                "readOnly", true
        ));

        Map<String, String> environment = resolver.resolveEnvironment(server);

        assertThat(environment)
                .containsEntry("MYSQL_HOST", "mysql.internal")
                .containsEntry("MYSQL_PORT", "3306")
                .containsEntry("MYSQL_USER", "readonly")
                .containsEntry("MYSQL_PASS", "mysql-pass")
                .containsEntry("MYSQL_DB", "app")
                .containsEntry("ALLOW_INSERT_OPERATION", "false")
                .containsEntry("ALLOW_UPDATE_OPERATION", "false")
                .containsEntry("ALLOW_DELETE_OPERATION", "false");
    }

    @Test
    void shouldResolveOracleCommandWithDefaultBinary() {
        McpServer server = new McpServer();
        server.setServerType(McpServerTypeEnum.DATABASE.getCode());
        server.setPresetCode("oracle");

        List<String> command = resolver.resolveCommand(server);

        assertThat(command).containsExactly("sql", "-mcp");
    }
}
