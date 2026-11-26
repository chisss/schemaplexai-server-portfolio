package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest;
import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.database.security.SqlReadOnlyGuard;
import com.schemaplexai.service.semantic.domain.model.query.CompiledQuery;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.BusinessStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.TechnicalStatus;
import com.schemaplexai.service.semantic.domain.service.QueryPlanCompilationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseSemanticQueryExecutorAdapterTest {

    @Mock
    private DatabaseSourceService databaseSourceService;

    @Test
    void bindsPlanParametersBeforeCallingExistingReadOnlyDatabaseService() {
        DatabaseQueryResultVO result = new DatabaseQueryResultVO();
        result.setRows(List.of(Map.of("metric_1", 3)));
        result.setColumns(List.of("metric_1"));
        result.setRowCount(1);
        result.setTruncated(false);
        result.setElapsedMs(5L);
        result.setWarnings(List.of());
        when(databaseSourceService.executeQuery(eq("source-1"), any()))
                .thenReturn(result);

        var adapter = new DatabaseSemanticQueryExecutorAdapter(databaseSourceService);
        var executed = adapter.execute(plan(), new QueryExecutionLimits(100, 10));

        var captor = ArgumentCaptor.forClass(DatabaseQueryExecuteRequest.class);
        verify(databaseSourceService).executeQuery(eq("source-1"), captor.capture());
        assertThat(captor.getValue().getSql()).contains("\"status\" = 'completed'");
        assertThat(captor.getValue().getSql()).doesNotContain(":p1");
        assertThat(executed.rowCount()).isEqualTo(1);
    }

    @Test
    void mysqlPublishedPlanUsesReadOnlySourceAndKeepsExactMetric() {
        String object = "sales.orders";
        var intent = new QueryIntent("v1", "source-1",
                List.of(new QueryMetric("urn:amount", "订单金额", "sum", object, "amount")),
                List.of(new QueryDimension("urn:status", "状态", null, object, "status")),
                List.of(new QueryFilter("urn:status", "状态", "eq", "已完成", object, "status", true)),
                null, 200, new QueryOutput("table", "订单统计"), "release");
        QueryPlan plan = new QueryPlanCompilationService().compile(intent, "mysql");
        var guard = new SqlReadOnlyGuard();
        var sourceResult = new DatabaseQueryResultVO();
        sourceResult.setRows(List.of(Map.of("metric_1", "12345678901234567890.12", "dimension_1", "已完成")));
        sourceResult.setColumns(List.of("metric_1", "dimension_1"));
        sourceResult.setRowCount(1);
        sourceResult.setTruncated(false);
        sourceResult.setWarnings(List.of());

        var adapter = new DatabaseSemanticQueryExecutorAdapter(databaseSourceService);
        var limits = new QueryExecutionLimits(200, 10);
        when(databaseSourceService.executeSemanticQuery(eq("source-1"), any(), eq(10))).thenAnswer(call -> {
            DatabaseQueryExecuteRequest request = call.getArgument(1);
            guard.validate(request.getSql());
            return sourceResult;
        });

        adapter.explain(plan, limits);
        var result = adapter.execute(plan, limits);

        var requests = ArgumentCaptor.forClass(DatabaseQueryExecuteRequest.class);
        verify(databaseSourceService, times(2)).executeSemanticQuery(eq("source-1"), requests.capture(), eq(10));
        String executed = requests.getAllValues().getLast().getSql();
        assertThat(requests.getAllValues().getFirst().getSql()).isEqualTo("EXPLAIN " + executed);
        assertThat(executed).contains("CONVERT(X'e5b7b2e5ae8ce68890' USING utf8mb4)").doesNotContain(":p1");
        assertThat(result.rows()).containsExactly(
                Map.of("metric_1", new BigDecimal("12345678901234567890.12"), "dimension_1", "已完成"));
        assertThat(result.validation().technicalStatus()).isEqualTo(TechnicalStatus.PASSED);
        assertThat(result.validation().businessStatus()).isEqualTo(BusinessStatus.NOT_CHECKED);
    }

    private QueryPlan plan() {
        return new QueryPlan(
                "v1", "source-1", "postgresql", QueryDialect.POSTGRESQL,
                new CompiledQuery(
                        "SQL",
                        "SELECT 1 FROM \"orders\" WHERE \"orders\".\"status\" = :p1",
                        List.of(new QueryParameter("p1", "completed")), List.of(), List.of()),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", List.of());
    }
}
