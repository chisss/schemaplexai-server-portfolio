package com.schemaplexai.service.integration.mcp;

import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.common.enums.McpTransportTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.HashUtils;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import jakarta.annotation.PreDestroy;
import org.springframework.scheduling.annotation.Scheduled;
import java.nio.charset.StandardCharsets;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.database.credential.DatabaseCredentialMaterializer;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.DefaultMcpToolResultConverter;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpHeadersSupplier;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LangChain4j MCP Client 工厂
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LangChain4jMcpClientFactory {

    private static final Duration DEFAULT_INITIALIZATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_TOOL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_RESOURCE_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern MYSQL_QUERY_TIMING = Pattern.compile("Query execution time: [0-9]+\\.[0-9]{2} ms");

    private final DatabaseMcpPresetResolver databaseMcpPresetResolver;
    private final DatabaseCredentialMaterializer databaseCredentialMaterializer;

    private final Cache<PoolKey, McpClient> idleClients = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(Duration.ofMinutes(2))
            .executor(Runnable::run)
            .removalListener((PoolKey key, McpClient client, RemovalCause cause) -> {
                if (cause != RemovalCause.EXPLICIT) closeClient(client);
            })
            .build();
    private boolean closed;

    /** 复用远程通道，独占租借保证两个执行不会同时复用同一会话。 */
    public ClientLease borrow(String tenantId, String userId, McpServer server) {
        return borrow(tenantId, userId, server, null);
    }

    public ClientLease borrow(String tenantId, String userId, McpServer server, Duration timeout) {
        if (server == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "MCP Server 配置不能为空");
        }
        var type = McpTransportTypeEnum.fromCode(server.getTransportType());
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(userId) || !tenantId.equals(server.getTenantId())
                || !StringUtils.hasText(server.getId()) || type == McpTransportTypeEnum.STDIO
                || server.getConnectionConfig() != null && !server.getConnectionConfig().isEmpty()) {
            return new ClientLease(null, create(server, effectiveTimeout(timeout)));
        }
        String version = HashUtils.sha256Hex(Objects.toString(List.of(
                Objects.toString(server.getUpdatedAt(), ""), Objects.toString(server.getUrl(), ""),
                Objects.toString(server.getTransportType(), ""), Objects.toString(server.getHeaders(), ""),
                Objects.toString(server.getAuthType(), ""), Objects.toString(server.getAuthConfig(), "")))
                .getBytes(StandardCharsets.UTF_8));
        Duration toolTimeout = effectiveTimeout(timeout);
        String sessionUser = sharesStatelessSession(server, type) ? null : userId;
        PoolKey key = new PoolKey(tenantId, sessionUser, server.getId(), version, toolTimeout);
        McpClient client = idleClients.asMap().remove(key);
        return new ClientLease(key, client != null ? client : create(server, toolTimeout));
    }

    private boolean sharesStatelessSession(McpServer server, McpTransportTypeEnum type) {
        var transportConfig = server.getTransportConfig();
        String authType = server.getAuthType();
        return type == McpTransportTypeEnum.STREAMABLE_HTTP
                && transportConfig != null && Boolean.TRUE.equals(transportConfig.get("stateless"))
                && (!StringUtils.hasText(authType) || "none".equalsIgnoreCase(authType.strip()))
                && CollectionUtils.isEmpty(server.getAuthConfig()) && CollectionUtils.isEmpty(server.getHeaders());
    }

    private Duration effectiveTimeout(Duration timeout) {
        return timeout == null ? DEFAULT_TOOL_TIMEOUT : timeout;
    }

    @Scheduled(fixedDelay = 30_000)
    void evictIdleClients() {
        idleClients.cleanUp();
    }

    @PreDestroy
    synchronized void shutdown() {
        closed = true;
        idleClients.asMap().values().forEach(this::closeClient);
        idleClients.invalidateAll();
    }

    private void closeClient(McpClient client) {
        try {
            client.close();
        } catch (Exception e) {
            log.debug("关闭 MCP 客户端失败: {}", e.getClass().getSimpleName());
        }
    }

    private synchronized void returnClient(PoolKey key, McpClient client) {
        if (closed || key == null || idleClients.asMap().putIfAbsent(key, client) != null) {
            closeClient(client);
        }
    }

    private record PoolKey(String tenantId, String userId, String serverId, String version, Duration toolTimeout) { }

    public final class ClientLease implements AutoCloseable {
        private final PoolKey key;
        private final McpClient client;
        private final AtomicBoolean returned = new AtomicBoolean();

        private ClientLease(PoolKey key, McpClient client) {
            this.key = key;
            this.client = client;
        }

        public McpClient client() { return client; }

        public void invalidate() {
            if (returned.compareAndSet(false, true)) closeClient(client);
        }

        @Override
        public void close() {
            if (returned.compareAndSet(false, true)) returnClient(key, client);
        }
    }

    public McpClient create(McpServer server) {
        return create(server, DEFAULT_TOOL_TIMEOUT);
    }

    public McpClient create(McpServer server, Duration toolTimeout) {
        if (server == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "MCP Server 配置不能为空");
        }
        McpServer runtimeServer = databaseCredentialMaterializer.materialize(server);
        McpTransport transport = buildTransport(runtimeServer, toolTimeout);
        try {
            return buildClient(server, transport, toolTimeout);
        } catch (RuntimeException failure) {
            try {
                transport.close();
            } catch (Exception cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private McpClient buildClient(McpServer server, McpTransport transport, Duration toolTimeout) {
        var builder = DefaultMcpClient.builder()
                .key(server.getId())
                .clientName("SchemaPlexAI")
                .clientVersion("1.0.0")
                .protocolVersion("2024-11-05")
                .transport(transport)
                .initializationTimeout(DEFAULT_INITIALIZATION_TIMEOUT)
                .toolExecutionTimeout(toolTimeout)
                .resourcesTimeout(DEFAULT_RESOURCE_TIMEOUT)
                .promptsTimeout(DEFAULT_RESOURCE_TIMEOUT)
                .cacheToolList(true)
                .autoHealthCheck(false);
        if (McpServerTypeEnum.DATABASE.getCode().equals(server.getServerType())
                && server.getConnectionConfig() != null
                && "mysql".equalsIgnoreCase(Objects.toString(server.getConnectionConfig().get("databaseType"), ""))) {
            var converter = new DefaultMcpToolResultConverter();
            builder.toolResultConverter((content, isError) -> converter.convert(mysqlQueryContent(content, isError), isError));
        }
        return builder.build();
    }

    static List<Map<String, Object>> mysqlQueryContent(List<Map<String, Object>> content, boolean isError) {
        // MySQL MCP 的数据块与计时块分开，保留完整 JSON 数据块供查询结果校验。
        if (isError || content.size() != 2) {
            return content;
        }
        var rows = content.getFirst();
        var timing = content.getLast();
        if ("text".equals(rows.get("type")) && rows.get("text") instanceof String text
                && text.stripLeading().startsWith("[") && "text".equals(timing.get("type"))
                && timing.get("text") instanceof String duration && MYSQL_QUERY_TIMING.matcher(duration).matches()) {
            return List.of(rows);
        }
        return content;
    }

    private McpTransport buildTransport(McpServer server, Duration toolTimeout) {
        McpTransportTypeEnum transportType = McpTransportTypeEnum.fromCode(server.getTransportType());
        if (transportType == null) {
            transportType = McpTransportTypeEnum.STREAMABLE_HTTP;
        }
        return switch (transportType) {
            case STREAMABLE_HTTP -> StreamableHttpMcpTransport.builder()
                    .url(requireUrl(server))
                    .customHeaders(resolveHeadersSupplier(server))
                    .timeout(toolTimeout)
                    .build();
            case SSE -> StreamableHttpMcpTransport.builder()
                    .url(requireUrl(server))
                    .customHeaders(resolveHeadersSupplier(server))
                    .timeout(toolTimeout)
                    .subsidiaryChannel(true)
                    .build();
            case LEGACY_SSE -> new LegacySseMcpTransport(requireUrl(server), resolveHeadersSupplier(server),
                    DEFAULT_INITIALIZATION_TIMEOUT);
            case STDIO -> StdioMcpTransport.builder()
                    .command(resolveCommand(server))
                    .environment(resolveEnvironment(server))
                    .build();
        };
    }

    private String requireUrl(McpServer server) {
        if (!StringUtils.hasText(server.getUrl())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前 MCP Server 传输方式要求提供 URL");
        }
        return server.getUrl().trim();
    }

    private McpHeadersSupplier resolveHeadersSupplier(McpServer server) {
        Map<String, String> resolvedHeaders = resolveHeaders(server);
        return callContext -> resolvedHeaders;
    }

    private Map<String, String> resolveHeaders(McpServer server) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (server.getHeaders() != null) {
            server.getHeaders().forEach((key, value) -> {
                if (StringUtils.hasText(key) && value != null) {
                    headers.put(key, String.valueOf(value));
                }
            });
        }
        Map<String, Object> authConfig = server.getAuthConfig();
        String authType = server.getAuthType() != null ? server.getAuthType().trim().toLowerCase() : "none";
        if (authConfig == null || authConfig.isEmpty() || "none".equals(authType)) {
            return headers;
        }

        switch (authType) {
            case "bearer", "oauth" -> {
                String token = firstText(authConfig, "token", "access_token");
                if (StringUtils.hasText(token)) {
                    headers.put("Authorization", "Bearer " + token);
                }
            }
            case "api_key" -> {
                String apiKey = firstText(authConfig, "apiKey", "api_key");
                String headerName = firstText(authConfig, "apiKeyHeader", "header_name", "headerName");
                if (StringUtils.hasText(apiKey)) {
                    headers.put(StringUtils.hasText(headerName) ? headerName : "X-API-Key", apiKey);
                }
            }
            case "basic" -> {
                String username = firstText(authConfig, "username");
                String password = firstText(authConfig, "password");
                if (StringUtils.hasText(username) && password != null) {
                    String credential = Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
                    headers.put("Authorization", "Basic " + credential);
                }
            }
            default -> log.debug("忽略未识别的 MCP 认证类型: {}", authType);
        }
        return headers;
    }

    private List<String> resolveCommand(McpServer server) {
        Map<String, Object> transportConfig = server.getTransportConfig();
        if (transportConfig == null || transportConfig.isEmpty()) {
            List<String> presetCommand = databaseMcpPresetResolver.resolveCommand(server);
            if (!presetCommand.isEmpty()) {
                return presetCommand;
            }
            throw new BusinessException(ResultCode.BAD_REQUEST, "STDIO 类型 MCP Server 缺少 transportConfig.command");
        }
        Object command = transportConfig.get("command");
        if (command instanceof List<?> commandList && !CollectionUtils.isEmpty(commandList)) {
            List<String> resolved = new ArrayList<>(commandList.size());
            for (Object part : commandList) {
                if (part != null && StringUtils.hasText(String.valueOf(part))) {
                    resolved.add(String.valueOf(part));
                }
            }
            if (!resolved.isEmpty()) {
                return resolved;
            }
        }
        if (command instanceof String commandText && StringUtils.hasText(commandText)) {
            return List.of(commandText.trim());
        }
        List<String> presetCommand = databaseMcpPresetResolver.resolveCommand(server);
        if (!presetCommand.isEmpty()) {
            return presetCommand;
        }
        throw new BusinessException(ResultCode.BAD_REQUEST, "STDIO 类型 MCP Server transportConfig.command 无效");
    }

    private Map<String, String> resolveEnvironment(McpServer server) {
        return new LinkedHashMap<>(databaseMcpPresetResolver.resolveEnvironment(server));
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
}
