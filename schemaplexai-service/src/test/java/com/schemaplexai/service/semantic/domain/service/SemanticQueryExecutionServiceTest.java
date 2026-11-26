package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.CompiledQuery;
import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import com.schemaplexai.service.semantic.domain.model.query.QueryCalendar;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryLineage;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.time.LocalDate;
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

    @Test
    void signsReferenceBindingsAndFrozenCandidateWindow() {
        var signatures = new QueryPlanSignatureService("test-secret");
        var range = new QueryTimeRange("urn:date", null, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3),
                "orders", "paid_at", new QueryCalendar("America/New_York", "timestamp with time zone"));
        var reference = reference("reference_region", "reference_day", range.calendar().timeZone());
        var plan = comparisonPlan(reference, range);
        var signed = signatures.sign(plan);
        signatures.verify(signed);

        var changedRange = new QueryTimeRange(range.fieldIri(), null, range.from(), range.to().plusDays(1),
                range.physicalObject(), range.physicalField(), range.calendar());
        for (var tampered : List.of(
                comparisonPlan(reference("alternate_region", "reference_day", range.calendar().timeZone()), range),
                comparisonPlan(reference("reference_region", "alternate_day", range.calendar().timeZone()), range),
                comparisonPlan(reference("reference_region", "reference_day", "UTC"), range),
                comparisonPlan(reference, changedRange))) {
            assertThatThrownBy(() -> signatures.verify(new SignedQueryPlan(tampered, signed.signature())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("signature");
        }
    }

    private PublishedQueryReference reference(String dimensionColumn, String timeColumn, String zone) {
        return new PublishedQueryReference("dataset", "source-1", "orders", "v1", "checksum", "snapshot",
                "fingerprint", "amount", "urn:metric", "sum", "reporting.finance_reference", "reference_amount",
                null, "财务参考", "reference-binding", "source-revision", "amount",
                List.of(new PublishedQueryReference.Dimension("region", "urn:region", "region", "string",
                        "text", dimensionColumn)),
                new PublishedQueryReference.Time("paid_at", "urn:date", "paid_at", "dateTime",
                        "timestamp with time zone", zone, timeColumn));
    }

    private QueryPlan comparisonPlan(PublishedQueryReference reference, QueryTimeRange range) {
        var sample = plan();
        var query = sample.query();
        return new QueryPlan(sample.semanticVersionId(), sample.sourceId(), sample.databaseType(), sample.dialect(),
                new CompiledQuery(query.language(), query.statement(), query.parameters(), query.columns(),
                        query.lineage(), List.of(), range), sample.planHash(), List.of(), true, false, reference);
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
