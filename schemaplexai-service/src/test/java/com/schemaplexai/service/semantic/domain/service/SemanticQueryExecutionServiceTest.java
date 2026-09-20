package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.CompiledQuery;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryLineage;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticQueryExecutionServiceTest {

    @Test
    void signsPlanAndAuditsExplainAndExecuteWithoutExposingParameterValues() {
        List<String> actions = new ArrayList<>();
        SemanticQueryExecutorPort executor = new SemanticQueryExecutorPort() {
            @Override
            public QueryExplainResult explain(QueryPlan plan, QueryExecutionLimits limits) {
                return new QueryExplainResult(
                        plan.planHash(), "SQL", plan.query().statement(),
                        List.of("p1"), List.of(), List.of(), List.of());
            }

            @Override
            public QueryExecutionResult execute(QueryPlan plan, QueryExecutionLimits limits) {
                return new QueryExecutionResult(
                        plan.planHash(), List.of(Map.of("metric_1", 10)), List.of(), List.of(), 1,
                        false, 4, "adapter-audit", List.of());
            }
        };
        SemanticQueryAuditPort audit = event -> actions.add(event.action() + ":" + event.outcome());
        SemanticQueryExecutionService service = new SemanticQueryExecutionService(
                new QueryPlanSignatureService("test-secret"), new QueryPlanPolicy(), executor, audit);
        QueryPlan plan = plan();
        SignedQueryPlan signed = service.sign(plan);

        QueryExplainResult explained = service.explain(signed, "tenant-a", "user-a", QueryExecutionLimits.defaults());
        QueryExecutionResult executed = service.execute(signed, "tenant-a", "user-a", QueryExecutionLimits.defaults());

        assertThat(explained.parameterNames()).containsExactly("p1");
        assertThat(executed.rows()).containsExactly(Map.of("metric_1", 10));
        assertThat(actions).containsExactly("EXPLAIN:SUCCESS", "EXECUTE:SUCCESS");
        assertThat(explained.statement()).doesNotContain("completed");
    }

    @Test
    void rejectsTamperedPlanAndInvalidLimitsBeforeExecutor() {
        List<String> calls = new ArrayList<>();
        SemanticQueryExecutorPort executor = new SemanticQueryExecutorPort() {
            @Override
            public QueryExplainResult explain(QueryPlan plan, QueryExecutionLimits limits) {
                calls.add("explain");
                return new QueryExplainResult(plan.planHash(), "SQL", plan.query().statement(), List.of(), List.of(), List.of(), List.of());
            }

            @Override
            public QueryExecutionResult execute(QueryPlan plan, QueryExecutionLimits limits) {
                calls.add("execute");
                return new QueryExecutionResult(plan.planHash(), List.of(), List.of(), List.of(), 0, false, 0, "audit", List.of());
            }
        };
        SemanticQueryExecutionService service = new SemanticQueryExecutionService(
                new QueryPlanSignatureService("test-secret"), new QueryPlanPolicy(), executor, event -> { });
        SignedQueryPlan signed = service.sign(plan());
        QueryPlan tampered = new QueryPlan(
                "v1", "source-1", "postgresql", QueryDialect.POSTGRESQL,
                new CompiledQuery("SQL", "DELETE FROM orders", List.of(), List.of(), List.of()),
                signed.plan().planHash(), List.of());

        assertThatThrownBy(() -> service.execute(
                new SignedQueryPlan(tampered, signed.signature()), "tenant-a", "user-a", QueryExecutionLimits.defaults()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
        service.execute(signed, "tenant-a", "user-a", new QueryExecutionLimits(5000, 30));
        assertThat(calls).containsExactly("execute");
    }

    private QueryPlan plan() {
        return new QueryPlan(
                "v1", "source-1", "postgresql", QueryDialect.POSTGRESQL,
                new CompiledQuery(
                        "SQL",
                        "SELECT SUM(\"orders\".\"amount\") AS metric_1 FROM \"orders\" WHERE \"orders\".\"status\" = :p1",
                        List.of(new QueryParameter("p1", "completed")),
                        List.of(new QueryColumn("metric", "metric_1", "SUM(\"orders\".\"amount\")", "urn:metric")),
                        List.of(new QueryLineage("urn:metric", "source-1", "orders", "amount"))),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", List.of());
    }
}
