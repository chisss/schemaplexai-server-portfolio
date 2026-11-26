package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.service.semantic.domain.model.query.ExploratoryQuerySelection;
import com.schemaplexai.service.semantic.domain.model.query.ExploratoryQuerySelection.Aggregate;
import com.schemaplexai.service.semantic.domain.model.query.ExploratoryQuerySelection.Filter;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort.Field;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort.Request;
import com.schemaplexai.service.semantic.domain.service.QueryPlanCompilationService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExploratoryQueryCandidateApplicationServiceTest {

    private final ExploratoryQueryCandidatePort model = mock(ExploratoryQueryCandidatePort.class);
    private final ExploratoryQueryCandidateApplicationService service = new ExploratoryQueryCandidateApplicationService(model);
    private final List<SchemaField> fields = List.of(
            field("status", "character varying"),
            field("amount", "numeric"),
            field("payload", "jsonb"),
            field("unsafe field", "text"));

    @Test
    void projectsAllowedSchemaAndCompilesOnlyResolvedOperationsFromPinnedObject() {
        var candidate = new ExploratoryQuerySelection(
                List.of(new Aggregate("f1", "sum")),
                List.of("f2"),
                List.of(new Filter("f1", new BigDecimal("23.40")), new Filter("f2", "已完成")));
        when(model.propose(eq("tenant"), any())).thenReturn(candidate);

        String question = "按状态分组统计金额总和，仅看金额等于 23.40 且状态为已完成";
        var selection = service.propose("tenant", question, fields);
        verify(model).propose("tenant", new Request(question,
                List.of(new Field("f1", "amount", "decimal"), new Field("f2", "status", "string"))));

        var intent = service.toIntent(null, "source", "reporting.orders", "scope", fields.reversed(), selection);
        var plan = new QueryPlanCompilationService().compile(intent, "postgresql");
        assertThat(plan.query().statement()).contains("SUM(\"reporting\".\"orders\".\"amount\")",
                "\"reporting\".\"orders\".\"status\" = :p2", "GROUP BY", "LIMIT 201")
                .doesNotContain("23.40", "已完成", "payload");
        assertThat(plan.query().parameters().get(0).value()).isEqualTo("23.4");
        assertThat(plan.query().parameters().get(1).textValue()).isTrue();
        assertThat(plan.query().lineage()).allSatisfy(line -> {
            assertThat(line.physicalObject()).isEqualTo("reporting.orders");
            assertThat(line.physicalField()).isIn("amount", "status");
        });
    }

    @Test
    void compilesMysqlExactColumnsAndPreservesUnsignedBoundaryAndTextFilters() {
        var mysqlFields = List.of(field("amount", "decimal(18,2) unsigned"), field("kind", "varchar(24)"),
                field("units", "bigint unsigned"), field("payload", "json"), field("estimated", "double"));
        var unsignedLimit = new BigInteger("18446744073709551615");
        var candidate = new ExploratoryQuerySelection(List.of(new Aggregate("f1", "sum")),
                List.of("f2"), List.of(new Filter("f2", "01"), new Filter("f3", unsignedLimit)));
        String question = "sum amount by kind where kind = '01' and units = " + unsignedLimit;
        when(model.propose(eq("tenant"), any())).thenReturn(candidate);

        var selection = service.propose("tenant", question, mysqlFields);
        verify(model).propose("tenant", new Request(question, List.of(
                new Field("f1", "amount", "decimal"), new Field("f2", "kind", "string"),
                new Field("f3", "units", "integer"))));
        var intent = service.toIntent("version", "source", "reporting.orders", "scope", mysqlFields, selection);
        var plan = new QueryPlanCompilationService().compile(intent, "mysql");
        assertThat(plan.queryPath()).isEqualTo("NL2SQL");
        assertThat(plan.query().statement())
                .contains("CAST(SUM(`reporting`.`orders`.`amount`) AS CHAR) AS metric_1",
                        "CAST(`reporting`.`orders`.`kind` AS CHAR) AS dimension_1",
                        "WHERE `reporting`.`orders`.`kind` = :p1 AND `reporting`.`orders`.`units` = :p2",
                        "LIMIT 201")
                .doesNotContain(unsignedLimit.toString(), "payload", "estimated");
        assertThat(plan.query().parameters().getFirst().textValue()).isTrue();
        assertThat(plan.query().parameters().getLast().value()).isEqualTo(unsignedLimit.toString());

        for (BigInteger invalid : List.of(BigInteger.valueOf(-1), unsignedLimit.add(BigInteger.ONE))) {
            var outOfRange = new ExploratoryQuerySelection(candidate.aggregates(),
                    candidate.dimensionIds(), List.of(new Filter("f3", invalid)));
            assertThatThrownBy(() -> service.toIntent("version", "source", "reporting.orders",
                    "scope", mysqlFields, outOfRange)).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("等值筛选");
        }
    }

    @Test
    void compilesRequestedMysqlMonthlySumAndRejectsMissingOrUnapprovedMonth() {
        var fields = List.of(field("date_tran", "date"), field("trans_amt", "decimal(17,2)"));
        var aggregates = List.of(new Aggregate("f2", "sum"));
        var month = new ExploratoryQuerySelection.TimeGrouping("f1", "month");
        var candidate = new ExploratoryQuerySelection(aggregates, List.of(), List.of(), month);
        var service = new ExploratoryQueryCandidateApplicationService((tenant, request) -> candidate);

        var selection = service.propose("tenant", "按月统计还款金额", fields);
        var intent = service.toIntent("version", "source", "reporting.repayments", "scope", fields, selection);
        var plan = new QueryPlanCompilationService().compile(intent, "mysql");
        assertThat(plan.queryPath()).isEqualTo("NL2SQL");
        assertThat(plan.query().statement()).contains("SUM(`reporting`.`repayments`.`trans_amt`)",
                "GROUP BY DATE_FORMAT(`reporting`.`repayments`.`date_tran`, '%Y-%m-01')");
        assertThatThrownBy(() -> service.propose("tenant", "统计还款金额", fields))
                .hasMessageContaining("明确的月份分组");
        assertThatThrownBy(() -> service.propose("tenant", "按月统计还款金额",
                List.of(field("date_tran", "varchar(10)"), fields.getLast()))).hasMessageContaining("DATE 或 DATETIME");
        var droppedMonth = new ExploratoryQueryCandidateApplicationService((tenant, request) ->
                new ExploratoryQuerySelection(aggregates, List.of(), List.of()));
        assertThatThrownBy(() -> droppedMonth.propose("tenant", "按月统计还款金额", fields))
                .hasMessageContaining("明确的月份分组");
    }

    @Test
    void rejectsUnknownIdsUnsupportedAggregatesAndTypeMismatchedFilterValues() {
        var unknown = new ExploratoryQuerySelection(List.of(new Aggregate("f3", "count")), List.of(), List.of());
        var textSum = new ExploratoryQuerySelection(List.of(new Aggregate("f2", "sum")), List.of(), List.of());
        var mismatched = new ExploratoryQuerySelection(
                List.of(new Aggregate("f1", "sum")), List.of(), List.of(new Filter("f1", "10")));
        var rawFilter = new ExploratoryQuerySelection(
                List.of(new Aggregate("f1", "sum")), List.of(), List.of(new Filter("f1", Map.of("gt", 10))));
        var duplicate = new ExploratoryQuerySelection(
                List.of(new Aggregate("f1", "sum")), List.of("f2", "f2"), List.of());
        var unknownOperation = new ExploratoryQuerySelection(
                List.of(new Aggregate("f1", "raw")), List.of(), List.of());

        for (var selection : List.of(unknown, textSum, mismatched, rawFilter, duplicate, unknownOperation)) {
            assertThatThrownBy(() -> service.toIntent(null, "s", "public.orders", "scope", fields, selection))
                    .isInstanceOf(BusinessException.class);
        }
        verifyNoInteractions(model);
    }

    @Test
    void rejectsInventedOrPartialNumericFilterAndDoesNotGuessWhenModelAbstains() {
        var candidate = new ExploratoryQuerySelection(
                List.of(new Aggregate("f1", "sum")), List.of(), List.of(new Filter("f1", 12)));
        when(model.propose(eq("tenant"), any())).thenReturn(candidate).thenReturn(null);

        assertThatThrownBy(() -> service.propose("tenant", "金额为 120 的订单金额之和", fields))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未明确出现在问题中");
        assertThatThrownBy(() -> service.propose("tenant", "列出状态", fields)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("模型未能用授权字段完整表达");
    }

    @Test
    void rejectsMissingScopeOrFieldsBeforeProposing() {
        assertThatThrownBy(() -> service.propose(null, "查询订单", fields))
                .isInstanceOf(BusinessException.class).hasMessageContaining("租户上下文缺失");
        assertThatThrownBy(() -> service.propose("tenant", "查询订单", List.of(field("payload", "jsonb"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("缺少可探查的字段");
        verifyNoInteractions(model);
    }

    @Test
    void refusesUnsupportedRankingDateRangesExclusionAndWeekGroupingBeforeCallingModel() {
        for (String question : List.of("按状态排名前 5 个的金额", "查询 2025-12-01 到 2025-12-31 的订单",
                "按周统计金额", "查询最近三周的金额", "排除状态为失败的订单", "total amount by status top 10")) {
            assertThatThrownBy(() -> service.propose("tenant", question, fields)).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("其他高级条件");
        }
        verifyNoInteractions(model);
    }

    @Test
    void refusesCandidatesThatDropAnExplicitFilterDateOrGroup() {
        var aggregate = List.of(new Aggregate("f1", "sum"));
        when(model.propose(eq("tenant"), any())).thenReturn(
                new ExploratoryQuerySelection(aggregate, List.of(), List.of()),
                new ExploratoryQuerySelection(aggregate, List.of(), List.of(new Filter("f2", "已完成"))),
                new ExploratoryQuerySelection(aggregate, List.of(), List.of()),
                new ExploratoryQuerySelection(aggregate, List.of(), List.of()));
        for (String question : List.of("金额总和，状态等于已完成",
                "金额总和，仅看状态为已完成，且金额为 25", "2025-12-05 的订单金额", "按状态分组统计金额")) {
            assertThatThrownBy(() -> service.propose("tenant", question, fields)).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("未保留问题中的显式限制");
        }
    }

    @Test
    void checksTypedDateAndExplicitlyNamedColumn() {
        var dateFields = List.of(field("amount", "numeric"), field("created_date", "date"), field("status", "text"));
        var aggregate = List.of(new Aggregate("f1", "sum"));
        var dateFilter = new Filter("f2", "2025-12-05");
        var dateSelection = new ExploratoryQuerySelection(aggregate, List.of(), List.of(dateFilter));
        when(model.propose(eq("tenant"), any())).thenReturn(dateSelection);

        assertThat(service.propose("tenant", "sum amount where created_date = '2025-12-05'", dateFields))
                .isEqualTo(dateSelection);
        assertThatThrownBy(() -> service.propose("tenant", "sum amount where status = '2025-12-05'", dateFields))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未保留问题中的显式限制");
    }

    @Test
    void rejectsPartialChineseFilterAndWrongExplicitDimension() {
        var aggregate = List.of(new Aggregate("f1", "sum"));
        when(model.propose(eq("tenant"), any())).thenReturn(
                new ExploratoryQuerySelection(aggregate, List.of(), List.of(new Filter("f2", "已"))),
                new ExploratoryQuerySelection(aggregate, List.of("f1"), List.of()));
        assertThatThrownBy(() -> service.propose("tenant", "统计状态为已完成的订单金额", fields))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未明确出现在问题中");
        assertThatThrownBy(() -> service.propose("tenant", "sum amount group by status", fields))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未保留问题中的显式限制");
    }

    private SchemaField field(String name, String type) {
        return new SchemaField(name, type, true, "保密注释", Map.of("保密分布", 42L));
    }
}
