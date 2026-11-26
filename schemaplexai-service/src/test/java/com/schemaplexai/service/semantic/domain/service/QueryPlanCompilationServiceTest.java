package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryPlanCompilationServiceTest {

    private final QueryPlanCompilationService service = new QueryPlanCompilationService();

    @Test
    void compilesPostgresqlWithBoundParametersAndLineage() {
        var plan = service.compile(intent(), "postgresql");

        assertThat(plan.dialect().name()).isEqualTo("POSTGRESQL");
        assertThat(plan.query().language()).isEqualTo("SQL");
        assertThat(plan.query().statement())
                .contains("SUM(\"orders\".\"amount\")")
                .contains("WHERE \"orders\".\"status\" = :p1")
                .contains("CURRENT_TIMESTAMP - (:p_time_days * INTERVAL '1 day')")
                .doesNotContain("'completed'");
        assertThat(plan.query().parameters())
                .extracting("name", "value")
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("p1", "completed"),
                        org.assertj.core.groups.Tuple.tuple("p_time_days", "30"));
        assertThat(plan.query().lineage()).hasSize(4);
        assertThat(plan.planHash()).hasSize(64);
    }

    @Test
    void compilesMongoAsControlledAggregationWithBoundParameters() {
        var plan = service.compile(intent(), "mongodb");

        assertThat(plan.query().language()).isEqualTo("MONGO_AGGREGATION");
        assertThat(plan.query().statement())
                .contains("$match")
                .contains("$dateSubtract")
                .contains("$group")
                .contains("?p1")
                .doesNotContain("\"completed\"");
        assertThat(plan.query().parameters()).extracting("name").containsExactly("p1", "p_time_days");
    }

    @Test
    void adaptsMysqlAndClickhouseDateSyntax() {
        var mysql = service.compile(intent(), "mysql");
        var clickhouse = service.compile(intent(), "clickhouse");

        assertThat(mysql.query().statement())
                .contains("`orders`.`amount`")
                .contains("DATE_SUB(CURRENT_TIMESTAMP, INTERVAL :p_time_days DAY)");
        assertThat(clickhouse.query().statement())
                .contains("`orders`.`amount`")
                .contains("subtractDays(now(), :p_time_days)");
    }

    @Test
    void compilesMysqlPublishedMetricAndDimensionsUsingExactStringProjections() {
        String object = "sales.orders";
        var intent = new QueryIntent("v1", "source-1",
                List.of(new QueryMetric("urn:metric", "订单金额", "sum", object, "amount")),
                List.of(new QueryDimension("urn:city", "城市", null, object, "city")),
                List.of(new QueryFilter("urn:status", "状态", "eq", "01", object, "status", true)),
                null, 200, new QueryOutput("table", "统计指标"), "release");

        var planned = service.compile(intent, "mysql");

        assertThat(planned.queryPath()).isEqualTo("PUBLISHED_ONTOLOGY");
        assertThat(planned.query().statement()).isEqualTo(
                "SELECT CAST(SUM(`sales`.`orders`.`amount`) AS CHAR) AS metric_1, "
                + "CAST(`sales`.`orders`.`city` AS CHAR) AS dimension_1 FROM `sales`.`orders`"
                + " WHERE `sales`.`orders`.`status` = :p1 GROUP BY `sales`.`orders`.`city`"
                + " ORDER BY `sales`.`orders`.`city` LIMIT 201");
        assertThat(planned.query().parameters()).singleElement().satisfies(parameter -> {
            assertThat(parameter.value()).isEqualTo("01");
            assertThat(parameter.textValue()).isTrue();
        });
    }

    @Test
    void compilesMysqlStructuredTimeRangeAndRejectsUnsupportedExplorationDialects() {
        var input = intent();
        var ranged = new QueryIntent(input.semanticVersionId(), input.sourceId(), input.metrics(), input.dimensions(),
                input.filters(), input.timeRange(), input.limit(), input.output(), "release");
        var exploration = new QueryIntent(input.semanticVersionId(), input.sourceId(), input.metrics(), input.dimensions(),
                input.filters(), null, input.limit(), input.output(), "release", true);
        assertThat(service.compile(ranged, "mysql").query().statement())
                .contains("DATE_SUB(CURRENT_TIMESTAMP, INTERVAL :p_time_days DAY)");
        assertThatThrownBy(() -> service.compile(exploration, "clickhouse")).hasMessageContaining("不支持此类授权查询");
    }

    @Test
    void rejectsMultiplePhysicalObjectsAndUnsafeIdentifiers() {
        QueryIntent differentObject = new QueryIntent(
                "v1", "source-1",
                List.of(new QueryMetric("urn:metric", "金额", "sum", "orders", "amount")),
                List.of(new QueryDimension("urn:city", "城市", null, "customers", "city")),
                List.of(), null, 100, new QueryOutput("table", "test"));
        assertThatThrownBy(() -> service.compile(differentObject, "mysql"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one physical object");

        QueryIntent unsafe = new QueryIntent(
                "v1", "source-1",
                List.of(new QueryMetric("urn:metric", "金额", "sum", "orders;drop", "amount")),
                List.of(), List.of(), null, 100, new QueryOutput("table", "test"));
        assertThatThrownBy(() -> service.compile(unsafe, "mysql"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid physical identifier");
    }

    @Test
    void rejectsTimeRangeWithoutPhysicalMapping() {
        QueryIntent missingMapping = new QueryIntent(
                "v1", "source-1",
                List.of(new QueryMetric("urn:metric", "金额", "sum", "orders", "amount")),
                List.of(), List.of(), new QueryTimeRange("urn:time", 30, null, null),
                100, new QueryOutput("table", "test"));

        assertThatThrownBy(() -> service.compile(missingMapping, "clickhouse"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("time range is missing physical mapping");
    }

    private QueryIntent intent() {
        return new QueryIntent(
                "v1",
                "source-1",
                List.of(new QueryMetric("urn:metric", "订单金额", "sum", "orders", "amount")),
                List.of(new QueryDimension("urn:city", "城市", null, "orders", "city")),
                List.of(new QueryFilter("urn:status", "订单状态", "eq", "completed", "orders", "status")),
                new QueryTimeRange("urn:time", 30, null, null, "orders", "completed_at"),
                200,
                new QueryOutput("line", "时间序列指标"));
    }
}
