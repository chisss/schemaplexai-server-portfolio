package com.schemaplexai.service.semantic.infrastructure.schema;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.database.security.SqlReadOnlyGuard;
import com.schemaplexai.service.integration.mcp.McpClientService;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSession;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSessionFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 基于租户数据库 MCP 配置的元数据会话工厂。 */
@Component
@RequiredArgsConstructor
public class McpSchemaMetadataSessionFactory implements SchemaMetadataSessionFactory {

    private final McpServerMapper mcpServerMapper;
    private final McpClientService mcpClientService;
    private final SqlReadOnlyGuard sqlReadOnlyGuard;
    private final ObjectMapper objectMapper;

    @Override
    public SchemaMetadataSession open(String tenantId, String sourceId) {
        McpServer source = mcpServerMapper.selectOne(
                new LambdaQueryWrapper<McpServer>()
                        .eq(McpServer::getTenantId, tenantId)
                        .eq(McpServer::getId, sourceId)
                        .eq(McpServer::getServerType, McpServerTypeEnum.DATABASE.getCode())
                        .last("LIMIT 1"));
        if (source == null) {
            throw new BusinessException(ResultCode.MCP_SERVER_NOT_FOUND);
        }
        return new McpSchemaMetadataSession(
                source,
                mcpClientService,
                sqlReadOnlyGuard,
                new McpPayloadRows(objectMapper));
    }

    private static final class McpSchemaMetadataSession implements SchemaMetadataSession {

        private static final List<String> QUERY_TOOLS = List.of(
                "query", "execute_sql", "read_query", "sql_query", "run_query", "executeQuery");
        private static final List<String> QUERY_ARGUMENTS = List.of("sql", "query", "statement", "command");
        private static final Map<String, List<String>> METADATA_TOOLS = Map.of(
                "list_collections", List.of("list_collections", "collections", "mongodb_list_collections"),
                "describe_collection", List.of("describe_collection", "collection_schema", "infer_schema", "schema"),
                "list_indexes", List.of("list_indexes", "collection_indexes", "indexes"));

        private final McpServer source;
        private final McpClientService client;
        private final SqlReadOnlyGuard sqlGuard;
        private final McpPayloadRows payloadRows;
        private List<Map<String, Object>> tools;

        private McpSchemaMetadataSession(
                McpServer source,
                McpClientService client,
                SqlReadOnlyGuard sqlGuard,
                McpPayloadRows payloadRows) {
            this.source = source;
            this.client = client;
            this.sqlGuard = sqlGuard;
            this.payloadRows = payloadRows;
            this.tools = extractStoredTools(source);
        }

        @Override
        public String sourceId() {
            return source.getId();
        }

        @Override
        public String databaseType() {
            String configured = firstText(source.getConnectionConfig(), "databaseType", "type", "driver");
            return StringUtils.hasText(configured) ? configured : source.getPresetCode();
        }

        @Override
        public String defaultCatalog() {
            return firstText(source.getConnectionConfig(), "catalog");
        }

        @Override
        public String defaultSchema() {
            return firstText(source.getConnectionConfig(), "schema", "database", "databaseName", "dbName");
        }

        @Override
        public List<Map<String, Object>> query(String statement) {
            sqlGuard.validate(statement);
            String tool = requireTool(QUERY_TOOLS);
            RuntimeException lastFailure = null;
            for (String argument : QUERY_ARGUMENTS) {
                try {
                    return payloadRows.extract(client.toolsCall(source, tool, Map.of(argument, statement)));
                } catch (RuntimeException exception) {
                    lastFailure = exception;
                }
            }
            throw new BusinessException(ResultCode.SCHEMA_SCAN_FAILED,
                    lastFailure == null ? "Schema 元数据查询失败" : "Schema 元数据查询参数不兼容");
        }

        @Override
        public List<Map<String, Object>> invoke(String operation, Map<String, Object> arguments) {
            List<String> aliases = METADATA_TOOLS.get(operation);
            if (aliases == null) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的 Schema 元数据操作");
            }
            String tool = requireTool(aliases);
            return payloadRows.extract(client.toolsCall(source, tool, arguments));
        }

        private String requireTool(List<String> candidates) {
            if (tools.isEmpty()) {
                tools = client.discoverTools(source);
            }
            for (String candidate : candidates) {
                for (Map<String, Object> tool : tools) {
                    String name = firstText(tool, "name");
                    if (name != null && name.equalsIgnoreCase(candidate)) {
                        return name;
                    }
                }
            }
            throw new BusinessException(ResultCode.SCHEMA_SCAN_FAILED, "数据源未提供所需的元数据工具");
        }

        private static List<Map<String, Object>> extractStoredTools(McpServer source) {
            if (source.getTools() == null) {
                return new ArrayList<>();
            }
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object tool : source.getTools()) {
                if (tool instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, value) -> {
                        if (key != null) {
                            normalized.put(String.valueOf(key), value);
                        }
                    });
                    result.add(normalized);
                }
            }
            return result;
        }

        private static String firstText(Map<String, ?> source, String... keys) {
            if (source == null) {
                return null;
            }
            for (String key : keys) {
                Object value = source.get(key);
                if (value != null && StringUtils.hasText(String.valueOf(value))) {
                    return String.valueOf(value).trim();
                }
            }
            return null;
        }
    }
}
