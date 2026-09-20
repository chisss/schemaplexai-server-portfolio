package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.McpServerTypeEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.integration.mcp.McpClientService;
import com.schemaplexai.service.semantic.domain.model.query.CompiledQuery;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryLineage;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MongoAggregationSemanticQueryExecutorAdapterTest {

    @Mock
    private McpServerMapper mapper;
    @Mock
    private McpClientService client;

    @AfterEach
    void clearSecurityContext() {
        SecurityUtil.clear();
    }

    @Test
    void bindsParametersAsJsonValuesAndAppliesResultLimit() {
        SecurityUtil.setCurrentTenantId("tenant-a");
        when(mapper.selectOne(any())).thenReturn(source("aggregate"));
        when(client.toolsCall(eq(source("aggregate")), eq("aggregate"), any()))
                .thenReturn(Map.of("documents", List.of(Map.of("metric_1", 1), Map.of("metric_1", 2))));

        var result = new MongoAggregationSemanticQueryExecutorAdapter(mapper, client, new ObjectMapper())
                .execute(plan("[{\"$match\": {\"status\": {\"$eq\": ?p1}}}, {\"$limit\": 2}]"),
                        new QueryExecutionLimits(1, 4));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> arguments = ArgumentCaptor.forClass(Map.class);
        verify(client).toolsCall(any(), eq("aggregate"), arguments.capture());
        assertThat(arguments.getValue()).containsEntry("collection", "orders").containsEntry("limit", 1);
        assertThat(arguments.getValue().get("pipeline").toString()).contains("completed").doesNotContain("?p1");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void rejectsWriteAndScriptStagesBeforeCallingMcp() {
        SecurityUtil.setCurrentTenantId("tenant-a");
        var adapter = new MongoAggregationSemanticQueryExecutorAdapter(mapper, client, new ObjectMapper());

        assertThatThrownBy(() -> adapter.execute(
                plan("[{\"$out\": \"exported\"}]"), new QueryExecutionLimits(100, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
        verify(client, never()).toolsCall(any(), any(), any());
    }

    @Test
    void explainDoesNotExecuteAggregationWhenExplainToolIsUnavailable() {
        SecurityUtil.setCurrentTenantId("tenant-a");
        when(mapper.selectOne(any())).thenReturn(source("aggregate"));

        var result = new MongoAggregationSemanticQueryExecutorAdapter(mapper, client, new ObjectMapper())
                .explain(plan("[{\"$group\": {\"_id\": null, \"metric_1\": {\"$sum\": \"$amount\"}}}]"),
                        QueryExecutionLimits.defaults());

        assertThat(result.warnings()).anyMatch(warning -> warning.contains("跳过真实执行"));
        verify(client, never()).toolsCall(any(), any(), any());
    }

    private McpServer source(String toolName) {
        McpServer source = new McpServer();
        source.setId("source-1");
        source.setTenantId("tenant-a");
        source.setServerType(McpServerTypeEnum.DATABASE.getCode());
        source.setTools(List.of(Map.of("name", toolName)));
        return source;
    }

    private QueryPlan plan(String statement) {
        return new QueryPlan(
                "v1", "source-1", "mongodb", QueryDialect.MONGODB,
                new CompiledQuery("MONGO_AGGREGATION", statement,
                        List.of(new QueryParameter("p1", "completed")),
                        List.of(new QueryColumn("metric", "metric_1", "$sum", "urn:metric")),
                        List.of(new QueryLineage("urn:metric", "source-1", "orders", "amount"))),
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", List.of());
    }
}
