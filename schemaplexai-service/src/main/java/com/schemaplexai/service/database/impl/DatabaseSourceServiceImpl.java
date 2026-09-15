package com.schemaplexai.service.database.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.common.enums.McpTransportTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest;
import com.schemaplexai.model.dto.database.DatabaseSourceQueryRequest;
import com.schemaplexai.model.dto.database.DatabaseSourceSaveRequest;
import com.schemaplexai.model.dto.mcp.McpServerCreateRequest;
import com.schemaplexai.model.dto.mcp.McpServerUpdateRequest;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.model.vo.database.DatabaseSourceTestVO;
import com.schemaplexai.model.vo.database.DatabaseSourceVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.database.credential.DatabaseCredentialMaterializer;
import com.schemaplexai.service.database.credential.DatabaseCredentialVault;
import com.schemaplexai.service.database.security.SqlReadOnlyGuard;
import com.schemaplexai.service.integration.mcp.DatabaseMcpPresetResolver;
import com.schemaplexai.service.integration.mcp.McpClientService;
import com.schemaplexai.service.mcp.McpServerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 数据库数据源服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatabaseSourceServiceImpl implements DatabaseSourceService {

    private static final List<String> QUERY_TOOL_NAME_PRIORITY = List.of(
            "query",
            "execute_sql",
            "read_query",
            "sql_query",
            "run_query",
            "executeQuery"
    );

    private static final List<String> QUERY_ARGUMENT_PRIORITY = List.of(
            "sql",
            "query",
            "statement",
            "command"
    );

    private final McpServerMapper mcpServerMapper;
    private final McpServerService mcpServerService;
    private final McpClientService mcpClientService;
    private final DatabaseMcpPresetResolver databaseMcpPresetResolver;
    private final DatabaseCredentialVault databaseCredentialVault;
    private final SqlReadOnlyGuard sqlReadOnlyGuard;
    private final ObjectMapper objectMapper;

    @Override
    public PageResult<DatabaseSourceVO> page(DatabaseSourceQueryRequest request) {
        List<McpServer> entities = mcpServerMapper.selectList(
                new LambdaQueryWrapper<McpServer>()
                        .eq(McpServer::getTenantId, requireTenantId())
                        .eq(McpServer::getServerType, McpServerTypeEnum.DATABASE.getCode())
                        .orderByDesc(McpServer::getCreatedAt)
        );

        List<DatabaseSourceVO> filtered = entities.stream()
                .map(this::toDatabaseSourceVO)
                .filter(item -> matchKeyword(item, request.getKeyword()))
                .filter(item -> matchEquals(item.getStatus(), request.getStatus()))
                .filter(item -> matchEquals(item.getDatabaseType(), request.getDatabaseType()))
                .filter(item -> matchEquals(item.getConnectionMode(), request.getConnectionMode()))
                .collect(Collectors.toList());

        int page = request.getPage() == null || request.getPage() < 1 ? 1 : request.getPage();
        int size = request.getSize() == null || request.getSize() < 1 ? 20 : request.getSize();
        int fromIndex = Math.min((page - 1) * size, filtered.size());
        int toIndex = Math.min(fromIndex + size, filtered.size());
        List<DatabaseSourceVO> records = filtered.subList(fromIndex, toIndex);
        return new PageResult<>(records, filtered.size(), page, size);
    }

    @Override
    public DatabaseSourceVO getById(String id) {
        McpServer entity = requireDatabaseSource(id);
        return toDatabaseSourceVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatabaseSourceVO create(DatabaseSourceSaveRequest request) {
        McpServerCreateRequest createRequest = new McpServerCreateRequest();
        createRequest.setName(request.getName());
        createRequest.setDescription(request.getDescription());
        createRequest.setUrl(blankToNull(request.getUrl()));
        createRequest.setTransportType(blankToNull(request.getTransportType()));
        createRequest.setAuthType(blankToNull(request.getAuthType()));
        createRequest.setAuthConfig(request.getAuthConfig());
        createRequest.setHeaders(defaultMap(request.getHeaders()));
        createRequest.setServerType(McpServerTypeEnum.DATABASE.getCode());
        createRequest.setPresetCode(blankToNull(request.getPresetCode()));
        Map<String, Object> connectionConfig = buildConnectionConfig(request, null);
        String secretRef = persistCredential(null, request, connectionConfig);
        createRequest.setConnectionConfig(toPersistedConnectionConfig(connectionConfig, secretRef));
        createRequest.setTransportConfig(buildTransportConfig(request, null));
        var created = mcpServerService.create(createRequest);
        return toDatabaseSourceVO(requireDatabaseSource(created.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatabaseSourceVO update(String id, DatabaseSourceSaveRequest request) {
        McpServer existing = requireDatabaseSource(id);

        McpServerUpdateRequest updateRequest = new McpServerUpdateRequest();
        updateRequest.setName(request.getName());
        updateRequest.setDescription(request.getDescription());
        updateRequest.setUrl(blankToNull(request.getUrl()));
        updateRequest.setTransportType(blankToNull(request.getTransportType()));
        updateRequest.setAuthType(blankToNull(request.getAuthType()));
        updateRequest.setAuthConfig(resolveAuthConfigForUpdate(request, existing));
        updateRequest.setHeaders(request.getHeaders() == null ? existing.getHeaders() : request.getHeaders());
        updateRequest.setServerType(McpServerTypeEnum.DATABASE.getCode());
        updateRequest.setPresetCode(blankToNull(request.getPresetCode()));
        Map<String, Object> connectionConfig = buildConnectionConfig(request, existing);
        String currentSecretRef = readString(existing.getConnectionConfig(), DatabaseCredentialMaterializer.SECRET_REF_KEY);
        String secretRef = persistCredential(currentSecretRef, request, connectionConfig);
        updateRequest.setConnectionConfig(toPersistedConnectionConfig(connectionConfig, secretRef));
        updateRequest.setTransportConfig(buildTransportConfig(request, existing));
        updateRequest.setStatus(existing.getStatus());
        mcpServerService.update(id, updateRequest);

        return toDatabaseSourceVO(requireDatabaseSource(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        McpServer source = requireDatabaseSource(id);
        mcpServerService.delete(id);
        databaseCredentialVault.delete(readString(
                source.getConnectionConfig(), DatabaseCredentialMaterializer.SECRET_REF_KEY));
    }

    @Override
    public DatabaseSourceTestVO testDraft(DatabaseSourceSaveRequest request) {
        McpServer draft = buildDraftServer(request);
        validateDatabaseDraft(draft);
        boolean connected = mcpClientService.healthCheck(draft);
        List<Map<String, Object>> tools = connected ? mcpClientService.discoverTools(draft) : List.of();

        DatabaseSourceTestVO result = new DatabaseSourceTestVO();
        result.setConnected(connected);
        result.setMessage(connected ? "连接成功" : "连接失败，请检查 MCP 配置或数据库网络连通性");
        result.setTools(tools);
        result.setToolCount(tools.size());
        result.setRecommendedQueryTool(resolveQueryTool(tools, readString(draft.getConnectionConfig(), "queryToolName")).toolName());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public DatabaseSourceTestVO test(String id) {
        requireDatabaseSource(id);
        mcpServerService.healthCheck(id);
        var discovered = mcpServerService.discoverTools(id);

        DatabaseSourceVO source = toDatabaseSourceVO(requireDatabaseSource(id));
        DatabaseSourceTestVO result = new DatabaseSourceTestVO();
        result.setConnected("active".equalsIgnoreCase(source.getStatus()));
        result.setMessage(result.isConnected() ? "连接成功" : "连接失败，请检查日志或重试");
        result.setTools(source.getTools());
        result.setToolCount(source.getToolCount());
        result.setRecommendedQueryTool(resolveQueryTool(source.getTools(), source.getQueryToolName()).toolName());

        log.info("数据库数据源测试完成: sourceId={}, connected={}, discoveredTools={}",
                id, result.isConnected(), discovered.getTools() != null ? discovered.getTools().size() : 0);
        return result;
    }

    @Override
    public DatabaseQueryResultVO executeQuery(String id, DatabaseQueryExecuteRequest request) {
        McpServer server = requireDatabaseSource(id);
        String sql = normalizeSql(request.getSql());
        sqlReadOnlyGuard.validate(sql);

        List<Map<String, Object>> tools = extractTools(server);
        if (tools.isEmpty()) {
            tools = mcpClientService.discoverTools(server);
        }
        QueryToolSelection toolSelection = resolveQueryTool(tools, readString(server.getConnectionConfig(), "queryToolName"));
        if (!StringUtils.hasText(toolSelection.toolName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未发现可执行查询的 MCP 工具，请先测试连接并完成工具发现");
        }

        long start = System.currentTimeMillis();
        Map<String, Object> rawPayload = executeQueryWithFallback(server, toolSelection, sql);
        long elapsed = System.currentTimeMillis() - start;

        ParsedQueryResult parsed = parseQueryResult(rawPayload);
        int limit = request.getLimit() == null || request.getLimit() < 1 ? 200 : request.getLimit();
        List<Map<String, Object>> rows = parsed.rows();
        boolean truncated = rows.size() > limit;
        List<Map<String, Object>> finalRows = truncated ? rows.subList(0, limit) : rows;

        DatabaseQueryResultVO result = new DatabaseQueryResultVO();
        result.setToolName(toolSelection.toolName());
        result.setColumns(resolveColumns(parsed.columns(), finalRows));
        result.setRows(finalRows);
        result.setRowCount(rows.size());
        result.setTruncated(truncated);
        result.setElapsedMs(elapsed);
        result.setRawPayload(rawPayload);
        result.setRawText(parsed.rawText());
        result.setWarnings(truncated ? List.of("结果已按限制行数截断返回") : List.of());
        return result;
    }

    private McpServer requireDatabaseSource(String id) {
        McpServer entity = mcpServerMapper.selectOne(
                new LambdaQueryWrapper<McpServer>()
                        .eq(McpServer::getId, id)
                        .eq(McpServer::getTenantId, requireTenantId())
                        .eq(McpServer::getServerType, McpServerTypeEnum.DATABASE.getCode())
                        .last("LIMIT 1")
        );
        if (entity == null) {
            throw new BusinessException(ResultCode.MCP_SERVER_NOT_FOUND);
        }
        return entity;
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }

    private DatabaseSourceVO toDatabaseSourceVO(McpServer entity) {
        DatabaseSourceVO vo = new DatabaseSourceVO();
        Map<String, Object> connectionConfig = defaultMap(entity.getConnectionConfig());
        Map<String, Object> transportConfig = defaultMap(entity.getTransportConfig());
        List<Map<String, Object>> tools = extractTools(entity);

        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setDescription(entity.getDescription());
        vo.setDatabaseType(resolveDatabaseType(entity, connectionConfig));
        vo.setConnectionMode(StringUtils.hasText(entity.getPresetCode()) ? "preset" : "custom");
        vo.setPresetCode(entity.getPresetCode());
        vo.setUrl(entity.getUrl());
        vo.setTransportType(entity.getTransportType());
        vo.setAuthType(entity.getAuthType());
        vo.setAuthConfig(maskSecretMap(entity.getAuthConfig()));
        vo.setHeaders(defaultMap(entity.getHeaders()));
        vo.setHost(readString(connectionConfig, "host"));
        vo.setPort(readString(connectionConfig, "port"));
        vo.setDatabase(readString(connectionConfig, "database"));
        vo.setSchema(readString(connectionConfig, "schema"));
        vo.setUsername(readString(connectionConfig, "username"));
        vo.setPasswordConfigured(StringUtils.hasText(readString(connectionConfig, "password"))
                || StringUtils.hasText(readString(connectionConfig, DatabaseCredentialMaterializer.SECRET_REF_KEY)));
        vo.setConnectionUri(maskConnectionUri(readString(connectionConfig, "connectionUri")));
        vo.setSslMode(readString(connectionConfig, "sslMode"));
        vo.setReadOnly(readBoolean(connectionConfig, "readOnly"));
        vo.setQueryToolName(readString(connectionConfig, "queryToolName"));
        vo.setTags(readStringList(connectionConfig, "tags"));
        vo.setCommand(readCommand(transportConfig));
        vo.setEnvironment(readStringMap(transportConfig, "environment"));
        vo.setConnectionConfig(maskSecretMap(connectionConfig));
        vo.setTransportConfig(transportConfig);
        vo.setTools(tools);
        vo.setToolCount(tools.size());
        vo.setStatus(entity.getStatus());
        vo.setLastHealthCheck(entity.getLastHealthCheck());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private boolean matchKeyword(DatabaseSourceVO item, String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return true;
        }
        String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
        return contains(item.getName(), normalizedKeyword)
                || contains(item.getDescription(), normalizedKeyword)
                || contains(item.getDatabaseType(), normalizedKeyword)
                || contains(item.getHost(), normalizedKeyword)
                || contains(item.getDatabase(), normalizedKeyword);
    }

    private boolean contains(String value, String keyword) {
        return StringUtils.hasText(value) && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private boolean matchEquals(String left, String right) {
        return !StringUtils.hasText(right) || Objects.equals(left, right);
    }

    private McpServer buildDraftServer(DatabaseSourceSaveRequest request) {
        McpServer server = new McpServer();
        server.setId("draft-" + UUID.randomUUID());
        server.setName(request.getName());
        server.setDescription(request.getDescription());
        server.setUrl(blankToNull(request.getUrl()));
        server.setTransportType(blankToNull(request.getTransportType()));
        server.setAuthType(blankToNull(request.getAuthType()));
        server.setAuthConfig(request.getAuthConfig());
        server.setHeaders(defaultMap(request.getHeaders()));
        server.setServerType(McpServerTypeEnum.DATABASE.getCode());
        server.setPresetCode(blankToNull(request.getPresetCode()));
        server.setConnectionConfig(buildConnectionConfig(request, null));
        server.setTransportConfig(buildTransportConfig(request, null));
        return server;
    }

    private void validateDatabaseDraft(McpServer server) {
        if (!StringUtils.hasText(server.getTransportType())) {
            server.setTransportType(McpTransportTypeEnum.STDIO.getCode());
        }

        databaseMcpPresetResolver.applyDefaults(server);
        databaseMcpPresetResolver.validate(server);

        McpTransportTypeEnum transportType = McpTransportTypeEnum.fromCode(server.getTransportType());
        if (transportType == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的 MCP 传输类型: " + server.getTransportType());
        }
        if ((transportType == McpTransportTypeEnum.STREAMABLE_HTTP || transportType == McpTransportTypeEnum.SSE)
                && !StringUtils.hasText(server.getUrl())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前传输类型必须填写 URL");
        }
        if (transportType == McpTransportTypeEnum.STDIO && readCommand(server.getTransportConfig()).isEmpty()
                && !StringUtils.hasText(server.getPresetCode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "STDIO 模式下必须提供命令或选择预设");
        }
    }

    private Map<String, Object> buildConnectionConfig(DatabaseSourceSaveRequest request, McpServer existing) {
        Map<String, Object> connectionConfig = new LinkedHashMap<>();
        putMergedText(connectionConfig, "databaseType", request.getDatabaseType(), existing);
        putMergedText(connectionConfig, "host", request.getHost(), existing);
        putMergedText(connectionConfig, "port", request.getPort(), existing);
        putMergedText(connectionConfig, "database", request.getDatabase(), existing);
        putMergedText(connectionConfig, "schema", request.getSchema(), existing);
        putMergedText(connectionConfig, "username", request.getUsername(), existing);
        putMergedText(connectionConfig, "connectionUri", request.getConnectionUri(), existing);
        putMergedText(connectionConfig, "sslMode", request.getSslMode(), existing);
        putMergedText(connectionConfig, "queryToolName", request.getQueryToolName(), existing);
        if (request.getReadOnly() != null) {
            connectionConfig.put("readOnly", request.getReadOnly());
        } else if (existing != null && existing.getConnectionConfig() != null && existing.getConnectionConfig().containsKey("readOnly")) {
            connectionConfig.put("readOnly", existing.getConnectionConfig().get("readOnly"));
        } else {
            connectionConfig.put("readOnly", Boolean.TRUE);
        }
        if (request.getTags() != null) {
            connectionConfig.put("tags", request.getTags());
        } else if (existing != null && existing.getConnectionConfig() != null && existing.getConnectionConfig().containsKey("tags")) {
            connectionConfig.put("tags", existing.getConnectionConfig().get("tags"));
        }

        String password = blankToNull(request.getPassword());
        if (password != null) {
            connectionConfig.put("password", password);
        } else if (existing != null && existing.getConnectionConfig() != null && existing.getConnectionConfig().containsKey("password")) {
            connectionConfig.put("password", existing.getConnectionConfig().get("password"));
        }
        return connectionConfig;
    }

    private Map<String, Object> buildTransportConfig(DatabaseSourceSaveRequest request, McpServer existing) {
        Map<String, Object> transportConfig = new LinkedHashMap<>();

        List<String> command = request.getCommand();
        if (command != null) {
            List<String> normalizedCommand = command.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .collect(Collectors.toList());
            if (!normalizedCommand.isEmpty()) {
                transportConfig.put("command", normalizedCommand);
            }
        } else if (existing != null && existing.getTransportConfig() != null && existing.getTransportConfig().containsKey("command")) {
            transportConfig.put("command", existing.getTransportConfig().get("command"));
        }

        if (request.getEnvironment() != null) {
            transportConfig.put("environment", request.getEnvironment());
        } else if (existing != null && existing.getTransportConfig() != null && existing.getTransportConfig().containsKey("environment")) {
            transportConfig.put("environment", existing.getTransportConfig().get("environment"));
        }

        return transportConfig;
    }

    private String persistCredential(String currentSecretRef,
                                     DatabaseSourceSaveRequest request,
                                     Map<String, Object> connectionConfig) {
        boolean submittedCredential = StringUtils.hasText(request.getPassword())
                || StringUtils.hasText(request.getConnectionUri());
        boolean legacyCredential = !StringUtils.hasText(currentSecretRef)
                && (StringUtils.hasText(readString(connectionConfig, "password"))
                || StringUtils.hasText(readString(connectionConfig, "connectionUri")));
        if (!submittedCredential && !legacyCredential) {
            return currentSecretRef;
        }

        Map<String, Object> credential = new LinkedHashMap<>();
        putIfText(credential, "password", readString(connectionConfig, "password"));
        putIfText(credential, "connectionUri", readString(connectionConfig, "connectionUri"));
        return databaseCredentialVault.save(currentSecretRef, credential);
    }

    private Map<String, Object> toPersistedConnectionConfig(Map<String, Object> connectionConfig,
                                                            String secretRef) {
        Map<String, Object> persisted = new LinkedHashMap<>(connectionConfig);
        persisted.remove("password");
        persisted.remove("connectionUri");
        if (StringUtils.hasText(secretRef)) {
            persisted.put(DatabaseCredentialMaterializer.SECRET_REF_KEY, secretRef);
        }
        return persisted;
    }

    private Map<String, Object> resolveAuthConfigForUpdate(DatabaseSourceSaveRequest request, McpServer existing) {
        if (request.getAuthConfig() != null) {
            return request.getAuthConfig();
        }
        return existing.getAuthConfig();
    }

    private String normalizeSql(String sql) {
        return sql == null ? "" : sql.trim();
    }

    private Map<String, Object> executeQueryWithFallback(McpServer server, QueryToolSelection toolSelection, String sql) {
        List<String> argumentKeys = new ArrayList<>(QUERY_ARGUMENT_PRIORITY);
        if (StringUtils.hasText(toolSelection.argumentName())) {
            argumentKeys.remove(toolSelection.argumentName());
            argumentKeys.add(0, toolSelection.argumentName());
        }

        RuntimeException lastError = null;
        for (String argumentKey : argumentKeys) {
            try {
                return mcpClientService.toolsCall(server, toolSelection.toolName(), Map.of(argumentKey, sql));
            } catch (RuntimeException exception) {
                lastError = exception;
                log.debug("数据库查询参数尝试失败: toolName={}, argumentKey={}, error={}",
                        toolSelection.toolName(), argumentKey, exception.getMessage());
            }
        }
        throw lastError != null ? lastError : new BusinessException(ResultCode.INTEGRATION_CONNECT_FAILED, "MCP 查询执行失败");
    }

    private QueryToolSelection resolveQueryTool(List<Map<String, Object>> tools, String configuredToolName) {
        if (StringUtils.hasText(configuredToolName)) {
            for (Map<String, Object> tool : tools) {
                if (configuredToolName.equals(String.valueOf(tool.get("name")))) {
                    return new QueryToolSelection(configuredToolName, resolveQueryArgument(tool));
                }
            }
            return new QueryToolSelection(configuredToolName, "sql");
        }

        for (String preferredName : QUERY_TOOL_NAME_PRIORITY) {
            for (Map<String, Object> tool : tools) {
                String toolName = String.valueOf(tool.get("name"));
                if (preferredName.equalsIgnoreCase(toolName)) {
                    return new QueryToolSelection(toolName, resolveQueryArgument(tool));
                }
            }
        }

        for (Map<String, Object> tool : tools) {
            String toolName = String.valueOf(tool.get("name"));
            String normalizedName = toolName.toLowerCase(Locale.ROOT);
            if ((normalizedName.contains("query") || normalizedName.contains("sql"))
                    && !normalizedName.contains("write")
                    && !normalizedName.contains("insert")
                    && !normalizedName.contains("delete")
                    && !normalizedName.contains("update")) {
                return new QueryToolSelection(toolName, resolveQueryArgument(tool));
            }
        }
        return new QueryToolSelection(null, null);
    }

    @SuppressWarnings("unchecked")
    private String resolveQueryArgument(Map<String, Object> tool) {
        Object inputSchema = tool.get("inputSchema");
        if (!(inputSchema instanceof Map<?, ?> schema)) {
            return "sql";
        }
        Object propertiesObject = schema.get("properties");
        if (!(propertiesObject instanceof Map<?, ?> properties)) {
            return "sql";
        }
        for (String key : QUERY_ARGUMENT_PRIORITY) {
            if (properties.containsKey(key)) {
                return key;
            }
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (entry.getKey() != null) {
                return String.valueOf(entry.getKey());
            }
        }
        return "sql";
    }

    @SuppressWarnings("unchecked")
    private ParsedQueryResult parseQueryResult(Map<String, Object> rawPayload) {
        if (rawPayload == null || rawPayload.isEmpty()) {
            return new ParsedQueryResult(List.of(), List.of(), null);
        }

        List<Map<String, Object>> directRows = extractRowsFromMap(rawPayload);
        if (!directRows.isEmpty()) {
            return new ParsedQueryResult(resolveColumns(List.of(), directRows), directRows, null);
        }

        Object content = rawPayload.get("content");
        if (content instanceof String textContent) {
            ParsedQueryResult parsed = parseTextContent(textContent);
            if (!parsed.rows().isEmpty() || StringUtils.hasText(parsed.rawText())) {
                return parsed;
            }
        }
        if (content instanceof List<?> contentList) {
            ParsedQueryResult parsed = parseContentList(contentList);
            if (!parsed.rows().isEmpty() || StringUtils.hasText(parsed.rawText())) {
                return parsed;
            }
        }

        String jsonText = safeWriteAsString(rawPayload);
        return new ParsedQueryResult(List.of(), List.of(), jsonText);
    }

    private ParsedQueryResult parseTextContent(String textContent) {
        if (!StringUtils.hasText(textContent)) {
            return new ParsedQueryResult(List.of(), List.of(), null);
        }
        String trimmed = textContent.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                Object parsed = objectMapper.readValue(trimmed, Object.class);
                if (parsed instanceof Map<?, ?> parsedMap) {
                    List<Map<String, Object>> rows = extractRowsFromMap(castMap(parsedMap));
                    if (!rows.isEmpty()) {
                        return new ParsedQueryResult(resolveColumns(List.of(), rows), rows, trimmed);
                    }
                }
                if (parsed instanceof List<?> parsedList) {
                    List<Map<String, Object>> rows = normalizeRowList(parsedList);
                    if (!rows.isEmpty()) {
                        return new ParsedQueryResult(resolveColumns(List.of(), rows), rows, trimmed);
                    }
                }
            } catch (JsonProcessingException ignored) {
                log.debug("查询文本结果不是 JSON，按原始文本返回");
            }
        }
        return new ParsedQueryResult(List.of(), List.of(), trimmed);
    }

    private ParsedQueryResult parseContentList(List<?> contentList) {
        StringBuilder builder = new StringBuilder();
        for (Object item : contentList) {
            if (item instanceof Map<?, ?> itemMap) {
                Map<String, Object> normalizedItem = castMap(itemMap);
                Object json = normalizedItem.get("json");
                if (json instanceof Map<?, ?> jsonMap) {
                    List<Map<String, Object>> rows = extractRowsFromMap(castMap(jsonMap));
                    if (!rows.isEmpty()) {
                        return new ParsedQueryResult(resolveColumns(List.of(), rows), rows, builder.toString());
                    }
                }
                Object data = normalizedItem.get("data");
                if (data instanceof Map<?, ?> dataMap) {
                    List<Map<String, Object>> rows = extractRowsFromMap(castMap(dataMap));
                    if (!rows.isEmpty()) {
                        return new ParsedQueryResult(resolveColumns(List.of(), rows), rows, builder.toString());
                    }
                }
                Object text = normalizedItem.get("text");
                if (text != null) {
                    if (builder.length() > 0) {
                        builder.append('\n');
                    }
                    builder.append(text);
                }
            } else if (item != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(item);
            }
        }
        return parseTextContent(builder.toString());
    }

    private List<Map<String, Object>> extractRowsFromMap(Map<String, Object> payload) {
        for (String key : List.of("rows", "data", "records", "result", "items")) {
            Object candidate = payload.get(key);
            if (candidate instanceof List<?> candidateList) {
                List<Map<String, Object>> rows = normalizeRowList(candidateList);
                if (!rows.isEmpty()) {
                    return rows;
                }
            }
            if (candidate instanceof Map<?, ?> candidateMap) {
                List<Map<String, Object>> nested = extractRowsFromMap(castMap(candidateMap));
                if (!nested.isEmpty()) {
                    return nested;
                }
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> normalizeRowList(List<?> candidateList) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object row : candidateList) {
            if (row instanceof Map<?, ?> rowMap) {
                rows.add(new LinkedHashMap<>(castMap(rowMap)));
            }
        }
        return rows;
    }

    private List<String> resolveColumns(List<String> explicitColumns, List<Map<String, Object>> rows) {
        if (explicitColumns != null && !explicitColumns.isEmpty()) {
            return explicitColumns;
        }
        Set<String> columns = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            columns.addAll(row.keySet());
        }
        return new ArrayList<>(columns);
    }

    private List<Map<String, Object>> extractTools(McpServer entity) {
        if (entity.getTools() == null) {
            return List.of();
        }
        return entity.getTools().stream()
                .filter(Objects::nonNull)
                .map(item -> objectMapper.convertValue(item, new TypeReference<Map<String, Object>>() { }))
                .collect(Collectors.toList());
    }

    private Map<String, Object> defaultMap(Map<String, Object> source) {
        return source == null ? Map.of() : source;
    }

    private void putIfText(Map<String, Object> target, String key, String value) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private void putMergedText(Map<String, Object> target, String key, String value, McpServer existing) {
        if (StringUtils.hasText(value)) {
            target.put(key, value.trim());
            return;
        }
        if (existing != null && existing.getConnectionConfig() != null && existing.getConnectionConfig().containsKey(key)) {
            target.put(key, existing.getConnectionConfig().get(key));
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String resolveDatabaseType(McpServer entity, Map<String, Object> connectionConfig) {
        String databaseType = readString(connectionConfig, "databaseType");
        if (StringUtils.hasText(databaseType)) {
            return databaseType;
        }
        return entity.getPresetCode();
    }

    private String readString(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Boolean readBoolean(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return Boolean.parseBoolean(stringValue);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value instanceof List<?> listValue) {
            return listValue.stream().filter(Objects::nonNull).map(String::valueOf).collect(Collectors.toList());
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readStringMap(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (!(value instanceof Map<?, ?> mapValue)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        mapValue.forEach((k, v) -> {
            if (k != null && v != null) {
                result.put(String.valueOf(k), String.valueOf(v));
            }
        });
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<String> readCommand(Map<String, Object> transportConfig) {
        Object command = transportConfig.get("command");
        if (command instanceof List<?> listCommand) {
            return listCommand.stream()
                    .filter(Objects::nonNull)
                    .map(String::valueOf)
                    .collect(Collectors.toList());
        }
        if (command instanceof String stringCommand && StringUtils.hasText(stringCommand)) {
            return List.of(stringCommand.trim());
        }
        return List.of();
    }

    private Map<String, Object> maskSecretMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((key, value) -> {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (normalizedKey.contains("password")
                    || normalizedKey.contains("secret")
                    || normalizedKey.contains("token")
                    || normalizedKey.contains("api_key")
                    || normalizedKey.contains("apikey")) {
                result.put(key, value == null ? null : "***");
            } else if ("connectionUri".equalsIgnoreCase(key) && value != null) {
                result.put(key, maskConnectionUri(String.valueOf(value)));
            } else {
                result.put(key, value);
            }
        });
        return result;
    }

    private String maskConnectionUri(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        int schemeIndex = value.indexOf("://");
        int atIndex = value.indexOf('@');
        int colonIndex = value.indexOf(':', schemeIndex >= 0 ? schemeIndex + 3 : 0);
        if (schemeIndex > -1 && atIndex > -1 && colonIndex > -1 && colonIndex < atIndex) {
            return value.substring(0, colonIndex + 1) + "***" + value.substring(atIndex);
        }
        return value;
    }

    private String safeWriteAsString(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            return String.valueOf(value);
        }
    }

    private Map<String, Object> castMap(Map<?, ?> source) {
        return objectMapper.convertValue(source, new TypeReference<Map<String, Object>>() { });
    }

    private record QueryToolSelection(String toolName, String argumentName) { }

    private record ParsedQueryResult(List<String> columns, List<Map<String, Object>> rows, String rawText) { }
}
