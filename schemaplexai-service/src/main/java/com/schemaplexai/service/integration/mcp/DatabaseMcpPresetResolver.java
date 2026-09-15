package com.schemaplexai.service.integration.mcp;

import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.common.enums.McpTransportTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.database.credential.DatabaseCredentialMaterializer;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库类 MCP 预设解析器
 */
@Component
public class DatabaseMcpPresetResolver {

    public void applyDefaults(McpServer server) {
        if (!isDatabasePreset(server)) {
            return;
        }
        requirePreset(server);
        if (!StringUtils.hasText(server.getTransportType())) {
            server.setTransportType(McpTransportTypeEnum.STDIO.getCode());
        }
    }

    public void validate(McpServer server) {
        if (!isDatabasePreset(server)) {
            return;
        }
        DatabasePreset preset = requirePreset(server);
        switch (preset) {
            case POSTGRESQL -> validatePostgresql(server);
            case MYSQL -> validateMysql(server);
            case ORACLE -> {
                // Oracle SQLcl MCP 可以先启动服务，再通过工具选择或管理保存连接
            }
        }
    }

    public List<String> resolveCommand(McpServer server) {
        if (!isDatabasePreset(server)) {
            return List.of();
        }
        DatabasePreset preset = requirePreset(server);
        return switch (preset) {
            case POSTGRESQL -> List.of("npx", "-y", "@modelcontextprotocol/server-postgres", resolvePostgresqlConnectionUri(server));
            case MYSQL -> List.of("npx", "-y", "@benborla29/mcp-server-mysql");
            case ORACLE -> List.of(resolveOracleBinary(server), "-mcp");
        };
    }

    public Map<String, String> resolveEnvironment(McpServer server) {
        Map<String, String> environment = new LinkedHashMap<>(extractEnvironment(server));
        if (!isDatabasePreset(server)) {
            return environment;
        }
        DatabasePreset preset = requirePreset(server);
        if (preset == DatabasePreset.MYSQL) {
            Map<String, Object> connectionConfig = safeMap(server.getConnectionConfig());
            putIfText(environment, "MYSQL_HOST", firstText(connectionConfig, "host", "hostname", "serverHost"));
            putIfText(environment, "MYSQL_PORT", firstText(connectionConfig, "port"));
            putIfText(environment, "MYSQL_USER", firstText(connectionConfig, "username", "user"));
            putIfText(environment, "MYSQL_PASS", firstText(connectionConfig, "password"));
            putIfText(environment, "MYSQL_DB", firstText(connectionConfig, "database", "databaseName", "dbName"));
            Object readOnly = connectionConfig.get("readOnly");
            if (readOnly instanceof Boolean readOnlyFlag) {
                String allowWrite = readOnlyFlag ? "false" : "true";
                environment.putIfAbsent("ALLOW_INSERT_OPERATION", allowWrite);
                environment.putIfAbsent("ALLOW_UPDATE_OPERATION", allowWrite);
                environment.putIfAbsent("ALLOW_DELETE_OPERATION", allowWrite);
            }
        }
        return environment;
    }

    public boolean isDatabasePreset(McpServer server) {
        return server != null
                && McpServerTypeEnum.DATABASE.getCode().equalsIgnoreCase(server.getServerType())
                && StringUtils.hasText(server.getPresetCode());
    }

    private void validatePostgresql(McpServer server) {
        Map<String, Object> connectionConfig = safeMap(server.getConnectionConfig());
        String connectionUri = firstText(connectionConfig, "connectionUri", "connectionUrl", "url", "jdbcUrl");
        if (StringUtils.hasText(connectionUri)
                || StringUtils.hasText(firstText(connectionConfig, DatabaseCredentialMaterializer.SECRET_REF_KEY))) {
            return;
        }
        if (!StringUtils.hasText(firstText(connectionConfig, "database", "databaseName", "dbName"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "PostgreSQL MCP 需要提供 database 或 connectionUri");
        }
        if (!StringUtils.hasText(firstText(connectionConfig, "username", "user"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "PostgreSQL MCP 需要提供 username 或 connectionUri");
        }
    }

    private void validateMysql(McpServer server) {
        Map<String, Object> connectionConfig = safeMap(server.getConnectionConfig());
        if (!StringUtils.hasText(firstText(connectionConfig, "host", "hostname", "serverHost"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "MySQL MCP 需要提供 host");
        }
        if (!StringUtils.hasText(firstText(connectionConfig, "database", "databaseName", "dbName"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "MySQL MCP 需要提供 database");
        }
        if (!StringUtils.hasText(firstText(connectionConfig, "username", "user"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "MySQL MCP 需要提供 username");
        }
    }

    private String resolvePostgresqlConnectionUri(McpServer server) {
        Map<String, Object> connectionConfig = safeMap(server.getConnectionConfig());
        String connectionUri = firstText(connectionConfig, "connectionUri", "connectionUrl", "url", "jdbcUrl");
        if (StringUtils.hasText(connectionUri)) {
            return connectionUri.trim();
        }

        String host = defaultIfBlank(firstText(connectionConfig, "host", "hostname", "serverHost"), "localhost");
        String port = defaultIfBlank(firstText(connectionConfig, "port"), "5432");
        String database = defaultIfBlank(firstText(connectionConfig, "database", "databaseName", "dbName"), "postgres");
        String username = defaultIfBlank(firstText(connectionConfig, "username", "user"), "postgres");
        String password = firstText(connectionConfig, "password");
        String sslMode = firstText(connectionConfig, "sslMode", "sslmode");

        StringBuilder builder = new StringBuilder("postgresql://");
        builder.append(urlEncode(username));
        if (password != null) {
            builder.append(':').append(urlEncode(password));
        }
        builder.append('@').append(host).append(':').append(port).append('/').append(database);
        if (StringUtils.hasText(sslMode)) {
            builder.append("?sslmode=").append(urlEncode(sslMode));
        }
        return builder.toString();
    }

    private String resolveOracleBinary(McpServer server) {
        Map<String, Object> connectionConfig = safeMap(server.getConnectionConfig());
        String sqlBinary = firstText(connectionConfig, "sqlBinaryPath", "sqlPath");
        return StringUtils.hasText(sqlBinary) ? sqlBinary : "sql";
    }

    private Map<String, Object> safeMap(Map<String, Object> source) {
        return source != null ? source : Map.of();
    }

    private Map<String, String> extractEnvironment(McpServer server) {
        Map<String, String> environment = new LinkedHashMap<>();
        Map<String, Object> transportConfig = safeMap(server.getTransportConfig());
        Object environmentObject = transportConfig.get("environment");
        if (environmentObject instanceof Map<?, ?> environmentMap) {
            environmentMap.forEach((key, value) -> {
                if (key != null && value != null && StringUtils.hasText(String.valueOf(key))) {
                    environment.put(String.valueOf(key), String.valueOf(value));
                }
            });
        }
        return environment;
    }

    private DatabasePreset requirePreset(McpServer server) {
        DatabasePreset preset = DatabasePreset.fromCode(server != null ? server.getPresetCode() : null);
        if (preset == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的数据库 MCP 预设: " + (server != null ? server.getPresetCode() : null));
        }
        return preset;
    }

    private String firstText(Map<String, Object> config, String... keys) {
        for (String key : keys) {
            Object value = config.get(key);
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.putIfAbsent(key, value.trim());
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private enum DatabasePreset {
        POSTGRESQL("postgresql"),
        MYSQL("mysql"),
        ORACLE("oracle");

        private final String code;

        DatabasePreset(String code) {
            this.code = code;
        }

        public static DatabasePreset fromCode(String code) {
            if (!StringUtils.hasText(code)) {
                return null;
            }
            for (DatabasePreset value : values()) {
                if (value.code.equalsIgnoreCase(code)) {
                    return value;
                }
            }
            return null;
        }
    }
}
