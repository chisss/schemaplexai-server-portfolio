package com.schemaplexai.service.integration.mcp;

import com.schemaplexai.common.enums.McpTransportTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.McpServer;
import dev.langchain4j.mcp.client.DefaultMcpClient;
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

    private final DatabaseMcpPresetResolver databaseMcpPresetResolver;

    public McpClient create(McpServer server) {
        if (server == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "MCP Server 配置不能为空");
        }
        return DefaultMcpClient.builder()
                .key(server.getId())
                .clientName("SchemaPlexAI")
                .clientVersion("1.0.0")
                .protocolVersion("2024-11-05")
                .transport(buildTransport(server))
                .initializationTimeout(DEFAULT_INITIALIZATION_TIMEOUT)
                .toolExecutionTimeout(DEFAULT_TOOL_TIMEOUT)
                .resourcesTimeout(DEFAULT_RESOURCE_TIMEOUT)
                .promptsTimeout(DEFAULT_RESOURCE_TIMEOUT)
                .cacheToolList(true)
                .autoHealthCheck(false)
                .build();
    }

    private McpTransport buildTransport(McpServer server) {
        McpTransportTypeEnum transportType = McpTransportTypeEnum.fromCode(server.getTransportType());
        if (transportType == null) {
            transportType = McpTransportTypeEnum.STREAMABLE_HTTP;
        }
        return switch (transportType) {
            case STREAMABLE_HTTP -> StreamableHttpMcpTransport.builder()
                    .url(requireUrl(server))
                    .customHeaders(resolveHeadersSupplier(server))
                    .timeout(DEFAULT_TOOL_TIMEOUT)
                    .build();
            case SSE -> StreamableHttpMcpTransport.builder()
                    .url(requireUrl(server))
                    .customHeaders(resolveHeadersSupplier(server))
                    .timeout(DEFAULT_TOOL_TIMEOUT)
                    .subsidiaryChannel(true)
                    .build();
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
