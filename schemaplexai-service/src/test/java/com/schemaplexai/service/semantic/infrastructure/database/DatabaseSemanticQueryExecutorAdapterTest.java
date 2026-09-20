package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.semantic.domain.model.query.CompiledQuery;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DatabaseSemanticQueryExecutorAdapterTest {

    @Mock
    private DatabaseSourceService databaseSourceService;

    @Test
    void bindsPlanParametersBeforeCallingExistingReadOnlyDatabaseService() {
        DatabaseQueryResultVO result = new DatabaseQueryResultVO();
        result.setRows(List.of(java.util.Map.of("metric_1", 3)));
        result.setColumns(List.of("metric_1"));
        result.setRowCount(1);
        result.setTruncated(false);
        result.setElapsedMs(5L);
        result.setWarnings(List.of());
        when(databaseSourceService.executeQuery(eq("source-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(result);

        var adapter = new DatabaseSemanticQueryExecutorAdapter(databaseSourceService);
        var executed = adapter.execute(plan(), new QueryExecutionLimits(100, 10));

        ArgumentCaptor<com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest> captor =
                ArgumentCaptor.forClass(com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest.class);
        verify(databaseSourceService).executeQuery(eq("source-1"), captor.capture());
        assertThat(captor.getValue().getSql()).contains("\"status\" = 'completed'");
        assertThat(captor.getValue().getSql()).doesNotContain(":p1");
        assertThat(executed.rowCount()).isEqualTo(1);
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
