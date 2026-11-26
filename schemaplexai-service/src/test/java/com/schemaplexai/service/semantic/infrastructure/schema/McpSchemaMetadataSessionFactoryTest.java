package com.schemaplexai.service.semantic.infrastructure.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.database.security.SqlReadOnlyGuard;
import com.schemaplexai.service.integration.mcp.McpClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpSchemaMetadataSessionFactoryTest {

    private static final String QUERY = "SELECT table_name FROM information_schema.tables";
    private final McpServerMapper mapper = mock(McpServerMapper.class);
    private final McpClientService client = mock(McpClientService.class);
    private final McpServer source = new McpServer();
    private final McpSchemaMetadataSessionFactory factory = new McpSchemaMetadataSessionFactory(
            mapper, client, new SqlReadOnlyGuard(), new ObjectMapper());

    @BeforeEach
    void setUp() {
        source.setConnectionConfig(Map.of("databaseType", "mysql"));
        when(mapper.selectOne(any())).thenReturn(source);
        when(client.discoverTools(source)).thenReturn(List.of(Map.of("name", "mysql_query")));
    }

    @Test
    void discoversMysqlQueryAndReadsTheAuthoritativeJsonRows() {
        when(client.toolsCall(source, "mysql_query", Map.of("sql", QUERY), Duration.ofSeconds(60)))
                .thenReturn(Map.of("content", "[{\"table_name\":\"orders\"}]"));

        try (var session = factory.open("tenant", "source")) {
            assertThat(session.query(QUERY)).containsExactly(Map.of("table_name", "orders"));
        }
    }

    @Test
    void treatsMysqlConnectorErrorsAsScanFailures() {
        when(client.toolsCall(eq(source), eq("mysql_query"), any(), eq(Duration.ofSeconds(60))))
                .thenThrow(new IllegalStateException("MCP 查询工具执行失败"));

        try (var session = factory.open("tenant", "source")) {
            assertThatThrownBy(() -> session.query(QUERY))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getCode()).isEqualTo(ResultCode.SCHEMA_SCAN_FAILED.getCode()));
        }
    }
}
