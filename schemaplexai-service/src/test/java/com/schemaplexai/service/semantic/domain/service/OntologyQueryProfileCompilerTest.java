package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Concept;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Property;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile.Dimension;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile.Filter;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile.Measure;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile.Time;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile.Value;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OntologyQueryProfileCompilerTest {

    private static final OntologyGraphRef REF = new OntologyGraphRef("tenant", "model", 1);
    private final OntologyQueryProfileCompiler compiler = new OntologyQueryProfileCompiler();

    @Test
    void compilesTypedRolesFromOneAuthorizedSnapshotWithNamedFilters() {
        var profile = new OntologyQueryProfile("ticket", "Asia/Shanghai",
                List.of(new Measure("amount", "sum"), new Measure("amount", "count")),
                List.of(new Dimension("region")), List.of(new Filter("region", List.of(new Value("华东", "EAST ")))),
                new Time("opened", List.of("day", "week", "month")));
        var compiled = compiler.compile(REF, configuration(profile), snapshot("postgresql"));

        assertThat(compiled.physicalObject()).isEqualTo("support.tickets");
        assertThat(compiled.sourceFingerprint()).isEqualTo("f1");
        assertThat(compiled.measures().getFirst().iri()).isEqualTo("urn:spx:tenant:tenant:semantic:model:property:amount");
        assertThat(compiled.dimensions()).hasSize(1);
        assertThat(compiled.filters().getFirst().values().getFirst().value()).isEqualTo("EAST ");
        assertThat(compiled.time().physicalType()).isEqualTo("timestamp with time zone");
    }

    @Test
    void rejectsUnsupportedDialectAndMissingOrForeignMappings() {
        assertThatThrownBy(() -> compiler.compile(REF, configuration(profile("amount", "sum")), snapshot("clickhouse")))
                .hasMessageContaining("PostgreSQL");
        assertThatThrownBy(() -> compiler.compile(REF, configuration(profile("unbound", "count")), snapshot("postgresql")))
                .hasMessageContaining("绑定的快照列");
        assertThatThrownBy(() -> compiler.compile(REF, configuration(profile("foreign", "sum")), snapshot("postgresql")))
                .hasMessageContaining("绑定的快照列");
        assertThatThrownBy(() -> compiler.compile(REF, configuration(profile("amount", "sum")), null))
                .hasMessageContaining("PostgreSQL");
    }

    @Test
    void compilesMysqlExactNumbersAndNamedCategories() {
        var profile = new OntologyQueryProfile("ticket", null, List.of(new Measure("amount", "sum")),
                List.of(new Dimension("region")), List.of(new Filter("region", List.of(new Value("华东", "EAST")))), null);
        var compiled = compiler.compile(REF, configuration(profile), snapshot("mysql"));

        assertThat(compiled.databaseType()).isEqualTo("mysql");
        assertThat(compiled.physicalObject()).isEqualTo("support.tickets");
        assertThat(compiled.measures().getFirst().physicalType()).isEqualTo("decimal");
        assertThat(compiled.dimensions().getFirst().physicalType()).isEqualTo("varchar");
        assertThat(compiled.filters().getFirst().values().getFirst().value()).isEqualTo("EAST");
        assertThat(compiled.time()).isNull();
    }

    @Test
    void compilesMysqlLocalTimeRoleAndRejectsApproximateNumericMetrics() {
        var withTime = new OntologyQueryProfile("ticket", "Asia/Shanghai", List.of(new Measure("amount", "max")),
                null, null, new Time("opened", List.of("month")));
        var compiled = compiler.compile(REF, configuration(withTime), snapshot("mysql"));
        assertThat(compiled.time().physicalType()).isEqualTo("datetime");
        assertThat(compiled.time().grains()).containsExactly("month");
        assertThatThrownBy(() -> compiler.compile(REF, configuration(profile("amount", "sum")),
                snapshot("mysql", "double", "varchar(40)")))
                .hasMessageContaining("不支持的 MySQL 查询字段类型");
    }

    @Test
    void plansPublishedMysqlMonthlySumWithCalendarDates() {
        var profile = new OntologyQueryProfile("ticket", "Asia/Manila", List.of(new Measure("amount", "sum")),
                null, null, new Time("opened", List.of("month")));
        var compiled = compiler.compile(REF, configuration(profile), snapshot("mysql"));
        var interpretation = new PublishedQueryInterpretationService(Clock.systemUTC())
                .interpret("按月统计总费用", "v1", compiled, "release");
        var plan = new QueryPlanCompilationService().compile(interpretation.intent(), "mysql");

        assertThat(plan.queryPath()).isEqualTo("PUBLISHED_ONTOLOGY");
        assertThat(plan.query().statement()).contains("SUM(`support`.`tickets`.`expense`)",
                "GROUP BY DATE_FORMAT(`support`.`tickets`.`opened_at`, '%Y-%m-01')");
    }

    @Test
    void rejectsIncompatibleTypesAndDuplicateBusinessValues() {
        assertThatThrownBy(() -> compiler.compile(REF, configuration(profile("region", "sum")), snapshot("postgresql")))
                .hasMessageContaining("数值类型");
        var wrongFilter = new OntologyQueryProfile("ticket", null, List.of(new Measure("amount", "avg")), null,
                List.of(new Filter("amount", List.of(new Value("金额", "12")))), null);
        assertThatThrownBy(() -> compiler.compile(REF, configuration(wrongFilter), snapshot("postgresql")))
                .hasMessageContaining("字符类型");
        var dupLabels = new OntologyQueryProfile("ticket", null, List.of(new Measure("amount", "max")), null,
                List.of(new Filter("region", List.of(new Value("East", "1"), new Value("east", "2")))), null);
        assertThatThrownBy(() -> compiler.compile(REF, configuration(dupLabels), snapshot("postgresql")))
                .hasMessageContaining("标签在本体查询中必须唯一");
    }

    @Test
    void requiresIanaTimeZoneAndSupportedGrainsForTimeRole() {
        var wrongZone = new OntologyQueryProfile("ticket", "+08:00", List.of(new Measure("amount", "min")), null,
                null, new Time("opened", List.of("day")));
        assertThatThrownBy(() -> compiler.compile(REF, configuration(wrongZone), snapshot("postgresql")))
                .hasMessageContaining("IANA");
        var wrongGrain = new OntologyQueryProfile("ticket", "UTC", List.of(new Measure("amount", "min")), null,
                null, new Time("opened", List.of("quarter")));
        assertThatThrownBy(() -> compiler.compile(REF, configuration(wrongGrain), snapshot("postgresql")))
                .hasMessageContaining("时间粒度");
        assertThat(compiler.compile(REF, configuration(profile("amount", "min")), snapshot("postgresql")).timeZone())
                .isEqualTo("UTC");
    }

    private OntologyQueryProfile profile(String property, String aggregation) {
        return new OntologyQueryProfile("ticket", null, List.of(new Measure(property, aggregation)), null, null, null);
    }

    private OntologyConfiguration configuration(OntologyQueryProfile profile) {
        return new OntologyConfiguration(
                List.of(new Concept("ticket", "Ticket", "工单", null, "support.tickets"),
                        new Concept("other", "Other", "其他", null, null)),
                List.of(new Property("amount", "ticket", "amount", "费用", "decimal", true, "expense"),
                        new Property("opened", "ticket", "opened", "创建时间", "dateTime", true, "opened_at"),
                        new Property("region", "ticket", "region", "地区", "string", true, "region"),
                        new Property("foreign", "other", "foreign", "其他字段", "decimal", true, "expense"),
                        new Property("unbound", "ticket", "unbound", "未映射", "decimal", false, null)),
                List.of(), profile);
    }

    private SchemaSnapshot snapshot(String databaseType) {
        return "mysql".equals(databaseType) ? snapshot(databaseType, "decimal(18,2) unsigned", "varchar(40)")
                : snapshot(databaseType, "numeric", "character varying");
    }

    private SchemaSnapshot snapshot(String databaseType, String amountType, String dimensionType) {
        var table = new SchemaObject("support", "tickets", SchemaObjectType.VIEW, null, null, null,
                List.of(new SchemaField("expense", amountType, false, null, Map.of()),
                        new SchemaField("opened_at", "mysql".equals(databaseType) ? "datetime(6)" : "timestamptz",
                                false, null, Map.of()),
                        new SchemaField("region", dimensionType, false, null, Map.of())),
                List.of(), List.of(), List.of());
        return SchemaSnapshot.create("snapshot", "tenant", "source", "f1",
                new DatabaseSchema(databaseType, null, "support", List.of(table), Set.of(), List.of()), LocalDateTime.now());
    }
}
