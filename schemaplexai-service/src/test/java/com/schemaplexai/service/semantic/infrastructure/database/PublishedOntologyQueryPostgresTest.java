package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.model.entity.McpServer;
import com.schemaplexai.service.database.impl.DatabaseSourceServiceImpl;
import com.schemaplexai.service.database.security.SqlReadOnlyGuard;
import com.schemaplexai.service.desktop.DesktopDataService;
import com.schemaplexai.service.desktop.DesktopDataService.DatasetDefinition;
import com.schemaplexai.service.desktop.DesktopWorkspaceService;
import com.schemaplexai.service.integration.mcp.McpClientService;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryExecutionApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryPlanApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.ExploratoryQueryCandidateApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.ExploratoryQueryScopeApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService.DimensionMapping;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService.Register;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import com.schemaplexai.service.semantic.domain.model.query.QueryAuditEvent;
import com.schemaplexai.service.semantic.domain.model.query.QueryCalendar;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.BusinessStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.ComparedWindow;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.ResultStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.TechnicalStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.query.ExploratoryQuerySelection;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import com.schemaplexai.service.semantic.domain.service.PublishedQueryInterpretationService;
import com.schemaplexai.service.semantic.domain.service.QueryPlanCompilationService;
import com.schemaplexai.service.semantic.domain.service.QueryPlanPolicy;
import com.schemaplexai.service.semantic.domain.service.QueryPlanSignatureService;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryExecutionService;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryInterpretationService;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class PublishedOntologyQueryPostgresTest {
    private static final String TENANT = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String VERSION = UUID.randomUUID().toString();
    private static final String SOURCE = UUID.randomUUID().toString();
    private static final String SNAPSHOT = UUID.randomUUID().toString();
    private static final String AUDIT_CHECKSUM = "e".repeat(64);
    private static final String LATEST = "最近2天按日查询总金额，状态等于正常";

    @Test
    void executesGrantedPublishedQueryOnPostgresWithSourceAndResultChecks() throws Exception {
        String url = System.getenv("ONTOLOGY_PG_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "需设置隔离的 PostgreSQL 集成测试库");
        String user = System.getenv().getOrDefault("ONTOLOGY_PG_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("ONTOLOGY_PG_TEST_PASSWORD", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "ontology_query_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                    url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
            verifyRuntime(jdbc, schema + ".orders");
        } finally {
            SecurityUtil.clear();
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void verifyRuntime(JdbcTemplate jdbc, String table) throws Exception {
        var json = new ObjectMapper();
        assertThat(json.readValue("""
                {"status":"READY","columns":[],"clarifications":[],"reasons":[],"filters":[],"rowLimit":200}
                """, DesktopDataService.Plan.class).queryPath()).isNull();
        var profile = profile(table);
        seed(jdbc, json, profile);
        SecurityUtil.setCurrentTenantId(TENANT);
        SecurityUtil.setCurrentUserId(USER);
        var authorization = mock(AuthorizationService.class);
        when(authorization.hasPermission("database:source:manage")).thenReturn(true);
        when(authorization.hasPermission("semantic:query:interpret")).thenReturn(true);
        when(authorization.hasPermission("semantic:query:execute")).thenReturn(true);
        var mapper = mock(McpServerMapper.class);
        var mcp = mock(McpClientService.class);
        var source = source();
        when(mapper.selectOne(any())).thenReturn(source);
        when(mcp.toolsCall(eq(source), eq("query"), any(), any(Duration.class)))
                .thenAnswer(call -> {
                    Map<String, String> arguments = call.getArgument(2);
                    return Map.of("rows", jdbc.queryForList(arguments.get("sql")));
                });
        var database = new DatabaseSourceServiceImpl(mapper, null, mcp, null, null, new SqlReadOnlyGuard(), json);
        var sourceAdapter = new DatabaseSemanticQuerySourceAdapter(database);
        var versions = mock(SemanticVersionRepository.class);
        var artifacts = mock(OntologyConfigurationStorePort.class);
        var legacy = mock(OntologyStorePort.class);
        var snapshots = mock(SchemaSnapshotRepository.class);
        var version = SemanticVersion.restore(VERSION, TENANT, "model", 1, "urn:ontology:order", SNAPSHOT,
                SemanticVersionStatus.PUBLISHED, AUDIT_CHECKSUM, 0, null, LocalDateTime.now(), 2);
        when(versions.findByTenantAndId(TENANT, VERSION)).thenReturn(Optional.of(version));
        when(artifacts.find(TENANT, VERSION)).thenReturn(Optional.of(
                new OntologyConfigurationArtifact(2, null, null, null, null, AUDIT_CHECKSUM, profile)));
        var schema = schema(table);
        when(snapshots.findByTenantAndId(TENANT, SNAPSHOT)).thenReturn(Optional.of(
                SchemaSnapshot.create(SNAPSHOT, TENANT, SOURCE, "fixture", schema, LocalDateTime.now())));
        var interpret = new SemanticQueryApplicationService(versions, legacy, new SemanticQueryInterpretationService(),
                artifacts, new PublishedQueryInterpretationService(Clock.fixed(
                    Instant.parse("2026-11-24T17:00:00Z"), ZoneOffset.UTC)), snapshots, sourceAdapter);
        var audits = new ArrayList<QueryAuditEvent>();
        var executor = new SemanticQueryExecutionService(new QueryPlanSignatureService("fixture"), new QueryPlanPolicy(),
                new DatabaseSemanticQueryExecutorAdapter(database), audits::add);
        var query = new SemanticQueryExecutionApplicationService(interpret,
                new SemanticQueryPlanApplicationService(new QueryPlanCompilationService()), executor, sourceAdapter);
        var datasets = new DesktopDataService(jdbc, json, null, authorization, database, query);
        assertThatThrownBy(() -> datasets.saveDataset(null, definition(table, List.of("absent"))))
                .isInstanceOf(BusinessException.class);
        var dataset = datasets.saveDataset(null, definition(table, List.of("amount", "state", "created_at")));
        assertThat(dataset.allowedFields()).containsExactly("amount", "state", "created_at");
        assertThatThrownBy(() -> datasets.queryAccess(dataset.id())).isInstanceOf(BusinessException.class);
        datasets.grantDataset(dataset.id(), new DesktopDataService.DatasetGrant(USER, false));
        assertThat(datasets.catalog()).extracting(DesktopDataService.Dataset::id).containsExactly(dataset.id());
        assertThat(json.valueToTree(datasets.catalog().getFirst()).has("allowedFields")).isFalse();
        var access = datasets.queryAccess(dataset.id());
        assertThat(access.revision()).isGreaterThan(dataset.policyRevision());

        assertThatThrownBy(() -> query.prepare(LATEST, VERSION, SOURCE))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        assertThat(query.prepare(LATEST + "但排除退款", VERSION, SOURCE, null, access).ready()).isFalse();
        assertThat(query.prepare("按区域查询金额", VERSION, SOURCE, null, access).ready()).isFalse();
        assertThat(query.prepare("查询金额", VERSION, SOURCE, null, access).ready()).isFalse();
        var plan = query.prepare(LATEST, VERSION, SOURCE, null, access);
        var other = query.prepare("最近2天按日查询总金额，状态等于异常", VERSION, SOURCE, null, access);
        var average = query.prepare("最近2天按日查询平均金额，状态等于正常", VERSION, SOURCE, null, access);
        assertThat(plan.ready()).isTrue();
        assertThat(plan.plan().queryPath()).isEqualTo("PUBLISHED_ONTOLOGY");
        assertThat(plan.plan().planHash()).isNotEqualTo(other.plan().planHash());
        assertThat(average.plan().query().statement()).contains("AVG(");
        assertThat(average.plan().planHash()).isNotEqualTo(plan.plan().planHash());
        assertThat(query.prepare("查询最大金额", VERSION, SOURCE, null, access).ready()).isFalse();
        assertThatThrownBy(() -> query.execute(other, plan.plan().planHash(), 200, 8))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED.getCode()));
        assertThat(query.explain(plan, plan.plan().planHash(), 200, 8).planHash()).isEqualTo(plan.plan().planHash());
        var result = query.execute(plan, plan.plan().planHash(), 200, 8);
        assertThat(result.rows()).extracting(row -> row.get("metric_1"))
                .containsExactly(new BigDecimal("10.25"), new BigDecimal("3.50"));
        assertThat(result.rows()).extracting(row -> row.get("dimension_1").toString())
                .containsExactly("2026-11-24", "2026-11-25");
        assertThat(result.auditId()).isEqualTo(audits.getLast().auditId());
        assertThat(result.validation().technicalStatus()).isEqualTo(TechnicalStatus.PASSED);
        assertThat(result.validation().resultStatus()).isEqualTo(ResultStatus.COMPLETE);
        assertThat(result.validation().businessStatus()).isEqualTo(BusinessStatus.NOT_CHECKED);
        verifyNoInteractions(legacy);

        when(mcp.toolsCall(eq(source), eq("query"), any(), eq(Duration.ofSeconds(9))))
                .thenReturn(Map.of("rows", List.of(Map.of("wrong_column", 4))));
        assertThatThrownBy(() -> query.execute(plan, plan.plan().planHash(), 200, 9))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ResultCode.SEMANTIC_QUERY_EXECUTION_UNAVAILABLE.getCode()));
        assertThat(audits.getLast().outcome()).isEqualTo("FAILED");
        when(mcp.toolsCall(eq(source), eq("query"), any(), eq(Duration.ofSeconds(9))))
                .thenReturn(Map.of("isError", true, "rows", List.of()));
        assertThatThrownBy(() -> query.execute(plan, plan.plan().planHash(), 200, 9))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ResultCode.SEMANTIC_QUERY_EXECUTION_UNAVAILABLE.getCode()));
        when(mcp.toolsCall(eq(source), eq("query"), any(), eq(Duration.ofSeconds(9))))
                .thenReturn(Map.of("rows", List.of()));
        var empty = query.execute(plan, plan.plan().planHash(), 200, 9);
        assertThat(empty.rows()).isEmpty();
        assertThat(empty.validation().resultStatus()).isEqualTo(ResultStatus.EMPTY);

        when(mcp.toolsCall(eq(source), eq("query"), any(), eq(Duration.ofSeconds(9))))
                .thenReturn(Map.of("rows", List.of(Map.of("dimension_1", "2026-11-24", "metric_1", "1"),
                        Map.of("dimension_1", "2026-11-24", "metric_1", "2"))));
        assertThatThrownBy(() -> query.execute(plan, plan.plan().planHash(), 200, 9))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重复的分组键");

        verifyReference(jdbc, table, authorization, database, sourceAdapter, versions, artifacts, snapshots, interpret);

        datasets.revokeDataset(dataset.id(), USER);
        assertThat(datasets.catalog()).extracting(DesktopDataService.Dataset::id).doesNotContain(dataset.id());
        datasets.grantDataset(dataset.id(), new DesktopDataService.DatasetGrant(USER, false));
        assertThat(query.prepare(LATEST, VERSION, SOURCE, null, datasets.queryAccess(dataset.id())).plan().planHash())
                .isNotEqualTo(plan.plan().planHash());
        String redisPort = System.getenv("ONTOLOGY_REDIS_TEST_PORT");
        if (redisPort != null && !redisPort.isBlank()) {
            verifyExploration(jdbc, json, table, authorization, database, sourceAdapter, versions, artifacts, snapshots,
                    interpret, executor, source, Integer.parseInt(redisPort));
        }
        SecurityUtil.setCurrentTenantId(UUID.randomUUID().toString());
        assertThat(datasets.catalog()).isEmpty();
        assertThatThrownBy(() -> query.prepare(LATEST, VERSION, SOURCE, null, access))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo(ResultCode.SEMANTIC_VERSION_NOT_FOUND.getCode()));
        verify(mcp).toolsCall(eq(source), eq("query"), eq(Map.of("sql",
                "EXPLAIN " + new QueryParameterBinder().bind(plan.plan().query().statement(), plan.plan().query().parameters()))),
                eq(Duration.ofSeconds(8)));
    }

    private void verifyReference(JdbcTemplate jdbc, String table, AuthorizationService authorization,
                                 DatabaseSourceServiceImpl database, DatabaseSemanticQuerySourceAdapter sourceAdapter,
                                 SemanticVersionRepository versions, OntologyConfigurationStorePort artifacts,
                                 SchemaSnapshotRepository snapshots, SemanticQueryApplicationService interpret) throws Exception {
        var json = new ObjectMapper().findAndRegisterModules();
        var configuration = new MybatisConfiguration();
        configuration.addMapper(AuditLogMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(jdbc.getDataSource());
        factory.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AuditLogMapper.class);
        var txManager = new DataSourceTransactionManager(jdbc.getDataSource());
        var audit = new ProxyFactory(new MybatisSemanticQueryAuditAdapter(mapper, json));
        audit.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        var executor = new SemanticQueryExecutionService(new QueryPlanSignatureService("fixture"), new QueryPlanPolicy(),
                new DatabaseSemanticQueryExecutorAdapter(database),
                (SemanticQueryAuditPort) audit.getProxy());
        var references = new PublishedQueryReferenceApplicationService(new JdbcPublishedQueryReferenceRepository(jdbc, json),
                versions, artifacts, snapshots, database, authorization);
        var query = new SemanticQueryExecutionApplicationService(interpret,
                new SemanticQueryPlanApplicationService(new QueryPlanCompilationService()), executor, sourceAdapter,
                null, null, null, references);
        var datasets = new DesktopDataService(jdbc, json, new DesktopWorkspaceService(jdbc, null),
                authorization, database, query);
        var dataset = datasets.saveDataset(null, definition(table, List.of("amount")));
        datasets.grantDataset(dataset.id(), new DesktopDataService.DatasetGrant(USER, true));
        var access = datasets.queryAccess(dataset.id());
        String reference = table.substring(0, table.indexOf('.')) + ".approved_revenue";
        var draft = query.prepare("查询总金额", VERSION, SOURCE, null, access);
        references.register(dataset.id(), new PublishedQueryReferenceApplicationService.Register(
                "amount", "sum", reference, "revenue", "0", "财务独立基准"));
        var plan = query.confirm("查询总金额", VERSION, SOURCE, null, access, draft.plan().planHash());
        assertThat(plan.plan().planHash()).isNotEqualTo(draft.plan().planHash());
        assertThatThrownBy(() -> query.execute(plan, draft.plan().planHash(), 200, 8))
                .isInstanceOf(BusinessException.class);
        var matched = query.execute(plan, plan.plan().planHash(), 200, 8);
        assertThat(matched.validation().businessStatus()).isEqualTo(BusinessStatus.MATCHED);
        assertThat(matched.validation().comparedRows()).isEqualTo(1);
        assertThat(matched.rows().getFirst()).containsOnlyKeys("metric_1");
        assertThat(jdbc.queryForObject("""
                SELECT detail::jsonb->>'businessStatus' FROM sf_audit_log WHERE id=CAST(? AS uuid)
                """, String.class, matched.auditId())).isEqualTo("MATCHED");

        jdbc.execute("CREATE OR REPLACE VIEW approved_revenue WITH (security_invoker=true) AS SELECT 0::numeric revenue");
        String conversation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sf_desktop_conversation(id,tenant_id,owner_id,type,title) VALUES(?,?,?,'data','查询')",
                conversation, TENANT, USER);
        var asked = datasets.ask(conversation, new DesktopDataService.Ask(dataset.id(), "查询总金额", null, "ref"));
        var mismatch = datasets.execute(conversation, asked.id(), asked.planHash());
        assertThat(mismatch.validation().businessStatus()).isEqualTo("MISMATCH");
        assertThat(datasets.result(mismatch.id()).validation()).isEqualTo(mismatch.validation());
        assertThatThrownBy(() -> datasets.export(new DesktopDataService.ExportRequest(mismatch.id(),
                mismatch.snapshotRevision(), "csv", List.of(), "currentResult", "blocked")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("对账有差异");

        jdbc.execute("ALTER VIEW approved_revenue SET (security_invoker=false)");
        var invalid = query.execute(plan, plan.plan().planHash(), 200, 8);
        assertThat(invalid.validation().businessStatus()).isEqualTo(BusinessStatus.NOT_CHECKED);
        jdbc.execute("DROP VIEW approved_revenue");
        var outer = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> outer.execute(status -> query.execute(plan, plan.plan().planHash(), 200, 8)))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM sf_audit_log WHERE detail::jsonb->>'outcome'='FAILED'
                    AND detail::jsonb->>'planHash'=?
                """, Integer.class, plan.plan().planHash())).isEqualTo(1);

        var groupedReference = verifyGroupedReference(jdbc, table, datasets, references, query);
        verifyDaylightSavingWindow(jdbc, table, database, groupedReference);
    }

    private PublishedQueryReference verifyGroupedReference(JdbcTemplate jdbc, String table, DesktopDataService datasets,
                                        PublishedQueryReferenceApplicationService references,
                                        SemanticQueryExecutionApplicationService query) {
        var dataset = datasets.saveDataset(null, definition(table, List.of("amount", "state", "created_at")));
        datasets.grantDataset(dataset.id(), new DesktopDataService.DatasetGrant(USER, true));
        var access = datasets.queryAccess(dataset.id());
        jdbc.execute("""
                INSERT INTO orders(amount, state, created_at) VALUES (2, NULL, '2026-11-24T00:00:00Z')
                """);
        jdbc.execute("INSERT INTO finance_orders SELECT amount, state, created_at FROM orders");
        jdbc.execute("INSERT INTO finance_orders VALUES (100, '01', '2026-11-23T15:59:59Z')");
        jdbc.execute("INSERT INTO finance_orders VALUES (100, '01', '2026-11-25T16:00:00Z')");
        String view = table.substring(0, table.indexOf('.')) + ".approved_daily_revenue";
        var reference = references.register(dataset.id(), new Register("amount", "sum", view, "revenue", null,
                "财务分组基准", List.of(new DimensionMapping("state", "group_key")), "event_at"));
        assertThat(references.get(dataset.id())).contains(reference);
        String question = "最近2天按状态查询总金额";
        var plan = query.prepare(question, VERSION, SOURCE, null, access);
        assertThat(plan.plan().reference()).isEqualTo(reference);
        query.explain(plan, plan.plan().planHash(), 200, 8);
        var matched = query.execute(plan, plan.plan().planHash(), 200, 8);
        assertThat(matched.validation().businessStatus()).isEqualTo(BusinessStatus.MATCHED);
        assertThat(matched.validation().comparedRows()).isEqualTo(3);
        assertThat(matched.validation().comparedWindow()).isEqualTo(
                new ComparedWindow("2026-11-24", "2026-11-25", "Asia/Shanghai"));
        assertThat(matched.rows()).extracting(row -> row.get("dimension_1")).containsExactly("01", "02", null);
        assertThat(matched.rows()).allSatisfy(row -> assertThat(row).containsOnlyKeys("metric_1", "dimension_1"));
        var limited = query.execute(plan, plan.plan().planHash(), 1, 8);
        assertThat(limited.rows()).hasSize(1);
        assertThat(limited.validation().resultStatus()).isEqualTo(ResultStatus.TRUNCATED);
        assertThat(limited.validation().businessStatus()).isEqualTo(BusinessStatus.NOT_CHECKED);
        assertThat(query.prepare(LATEST, VERSION, SOURCE, null, access).plan().reference()).isNull();
        assertThat(query.prepare(question + "，状态等于正常", VERSION, SOURCE, null, access).plan().reference()).isNull();
        assertThat(query.prepare("最近367天按状态查询总金额", VERSION, SOURCE, null, access).ready()).isFalse();

        jdbc.update("UPDATE finance_orders SET revenue = revenue + 1 WHERE group_key IS NULL");
        jdbc.update("DELETE FROM finance_orders WHERE group_key = '02'");
        jdbc.execute("INSERT INTO finance_orders VALUES (7, '03', '2026-11-24T16:00:00Z')");
        String conversation = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sf_desktop_conversation(id,tenant_id,owner_id,type,title) VALUES(?,?,?,'data','分组查询')",
                conversation, TENANT, USER);
        var asked = datasets.ask(conversation, new DesktopDataService.Ask(dataset.id(), question, null, "grouped"));
        var mismatch = datasets.execute(conversation, asked.id(), asked.planHash());
        assertThat(mismatch.validation().businessStatus()).isEqualTo("MISMATCH");
        assertThat(mismatch.validation().missingReferenceGroups()).isEqualTo(1);
        assertThat(mismatch.validation().extraReferenceGroups()).isEqualTo(1);
        assertThat(mismatch.validation().differingGroups()).isEqualTo(1);
        assertThat(datasets.result(mismatch.id()).validation()).isEqualTo(mismatch.validation());
        assertThatThrownBy(() -> datasets.export(new DesktopDataService.ExportRequest(mismatch.id(),
                mismatch.snapshotRevision(), "csv", List.of(), "currentResult", "grouped-blocked")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("对账有差异");
        assertThat(jdbc.queryForObject("""
                SELECT detail::jsonb->>'extraReferenceGroups' FROM sf_audit_log WHERE id=CAST(? AS uuid)
                """, String.class, mismatch.auditId())).isEqualTo("1");

        jdbc.execute("""
                INSERT INTO finance_orders
                SELECT 1, 'reference-' || groups, '2026-11-24T16:00:00Z'::timestamptz
                FROM generate_series(1, 201) AS groups
                """);
        var referenceOverLimit = query.execute(plan, plan.plan().planHash(), 200, 8);
        assertThat(referenceOverLimit.validation().businessStatus()).isEqualTo(BusinessStatus.NOT_CHECKED);
        assertThat(referenceOverLimit.validation().extraReferenceGroups()).isNull();
        jdbc.update("DELETE FROM finance_orders WHERE group_key LIKE 'reference-%'");
        jdbc.execute("""
                INSERT INTO orders(amount, state, created_at)
                SELECT 1, 'candidate-' || groups, '2026-11-24T16:00:00Z'::timestamptz
                FROM generate_series(1, 201) AS groups
                """);
        var candidateOverLimit = query.execute(plan, plan.plan().planHash(), 200, 8);
        assertThat(candidateOverLimit.rows()).hasSize(200);
        assertThat(candidateOverLimit.rowCount()).isEqualTo(201);
        assertThat(candidateOverLimit.validation().resultStatus()).isEqualTo(ResultStatus.TRUNCATED);
        assertThat(candidateOverLimit.validation().businessStatus()).isEqualTo(BusinessStatus.NOT_CHECKED);
        jdbc.update("DELETE FROM orders WHERE state LIKE 'candidate-%' OR state IS NULL");
        return reference;
    }

    private void verifyDaylightSavingWindow(JdbcTemplate jdbc, String table, DatabaseSourceServiceImpl database,
                                            PublishedQueryReference reference) {
        jdbc.execute("""
                INSERT INTO orders(amount, state, created_at) VALUES
                    (100, 'dst', '2026-03-08T04:59:59Z'),
                    (2, 'dst', '2026-03-08T05:00:00Z'),
                    (3, 'dst', '2026-03-09T03:59:59Z'),
                    (100, 'dst', '2026-03-09T04:00:00Z')
                """);
        jdbc.execute("""
                INSERT INTO finance_orders VALUES
                    (4, 'dst', '2026-03-08T05:00:00Z'),
                    (1, 'dst', '2026-03-09T03:59:59Z')
                """);
        var date = LocalDate.of(2026, 3, 8);
        var time = new QueryTimeRange("urn:time", null, date, date, table, "created_at",
                new QueryCalendar("America/New_York", "timestamp with time zone"));
        var intent = new QueryIntent(VERSION, SOURCE, List.of(new QueryMetric("urn:amount", "金额", "sum", table, "amount")),
                List.of(new QueryDimension("urn:state", "状态", null, table, "state")), List.of(), time, 200,
                new QueryOutput("table", "按状态"), "fixture");
        var signedPlan = new QueryPlanCompilationService().compile(intent, "postgresql");
        var binding = reference.time();
        var referenceWithZone = new PublishedQueryReference(reference.datasetId(), reference.sourceId(),
                reference.physicalObject(), reference.versionId(), reference.releaseChecksum(), reference.schemaSnapshotId(),
                reference.sourceFingerprint(), reference.measurePropertyId(), reference.measureIri(), reference.aggregation(),
                reference.referenceView(), reference.referenceColumn(), reference.tolerance(), reference.label(), reference.bindingKey(),
                reference.sourceRevision(), reference.measureColumn(), reference.dimensions(),
                new PublishedQueryReference.Time(binding.propertyId(), binding.iri(), binding.physicalField(),
                        binding.dataType(), binding.physicalType(), "America/New_York", binding.referenceColumn()));
        var result = new DatabaseSemanticQueryExecutorAdapter(database)
                .execute(signedPlan.withReference(referenceWithZone, signedPlan.planHash()), new QueryExecutionLimits(200, 8));
        assertThat(result.rows()).extracting(row -> row.get("metric_1")).containsExactly(new BigDecimal("5"));
        assertThat(result.validation().businessStatus()).isEqualTo(BusinessStatus.MATCHED);
        assertThat(result.validation().comparedWindow()).isEqualTo(new ComparedWindow(
                "2026-03-08", "2026-03-08", "America/New_York"));
        jdbc.update("DELETE FROM orders WHERE state = 'dst'");
    }

    private void verifyExploration(JdbcTemplate jdbc, ObjectMapper json, String table, AuthorizationService authorization,
                                   DatabaseSourceServiceImpl database, DatabaseSemanticQuerySourceAdapter sourceAdapter,
                                   SemanticVersionRepository versions, OntologyConfigurationStorePort artifacts,
                                   SchemaSnapshotRepository snapshots, SemanticQueryApplicationService interpret,
                                   SemanticQueryExecutionService executor, McpServer source, int redisPort) {
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", redisPort));
        factory.afterPropertiesSet();
        var redis = new StringRedisTemplate(factory);
        String question = "sum units where code = '01'";
        Set<String> originalKeys = redis.keys("semantic:exploration:*");
        try {
            var candidate = mock(ExploratoryQueryCandidatePort.class);
            when(candidate.propose(eq(TENANT), any())).thenAnswer(call -> {
                var request = call.getArgument(1, ExploratoryQueryCandidatePort.Request.class);
                assertThat(request.fields()).extracting(ExploratoryQueryCandidatePort.Field::name).containsExactly("code", "units");
                return new ExploratoryQuerySelection(List.of(new ExploratoryQuerySelection.Aggregate("f2", "sum")),
                        List.of(), List.of(new ExploratoryQuerySelection.Filter("f1", "01")));
            });
            var scope = new ExploratoryQueryScopeApplicationService(snapshots, versions, artifacts, database);
            var query = new SemanticQueryExecutionApplicationService(interpret,
                    new SemanticQueryPlanApplicationService(new QueryPlanCompilationService()), executor, sourceAdapter,
                    new ExploratoryQueryCandidateApplicationService(candidate), scope,
                    new RedisExploratoryQueryPlanStore(redis, json));
            var datasets = new DesktopDataService(jdbc, json.findAndRegisterModules(), new DesktopWorkspaceService(jdbc, null),
                    authorization, database, query, scope);
            var mixed = datasets.saveDataset(null, new DatasetDefinition("混合数据集", SOURCE, VERSION, table,
                    List.of("amount", "state", "created_at", "code", "units"), true, 0L, SNAPSHOT, true));
            var exploratory = datasets.saveDataset(null, new DatasetDefinition("探索数据集", SOURCE, null, table,
                    List.of("code", "units"), true, 0L, SNAPSHOT, true));
            datasets.grantDataset(mixed.id(), new DesktopDataService.DatasetGrant(USER, false));
            datasets.grantDataset(exploratory.id(), new DesktopDataService.DatasetGrant(USER, false));
            assertThat(datasets.catalog()).extracting(DesktopDataService.Dataset::id).contains(mixed.id()).doesNotContain(exploratory.id());
            var denied = datasets.queryAccess(mixed.id());
            assertThat(query.prepare(question, VERSION, SOURCE, null, denied).ready()).isFalse();
            verifyNoInteractions(candidate);

            when(authorization.hasPermission("semantic:query:explore")).thenReturn(true);
            var access = datasets.queryAccess(mixed.id());
            assertThat(query.prepare(LATEST, VERSION, SOURCE, null, access).plan().queryPath()).isEqualTo("PUBLISHED_ONTOLOGY");
            assertThat(query.prepare(question, VERSION, SOURCE, "金额", access).ready()).isFalse();
            verifyNoInteractions(candidate);
            assertThat(datasets.catalog()).extracting(DesktopDataService.Dataset::id).contains(exploratory.id());
            var plan = query.prepare(question, VERSION, SOURCE, null, access);
            assertThat(plan.plan().queryPath()).isEqualTo("NL2SQL");
            var confirmed = query.confirm(question, VERSION, SOURCE, null, access, plan.plan().planHash());
            assertThat(confirmed.signedPlan()).isEqualTo(plan.signedPlan());
            assertThat(confirmed.plan().query().parameters().getFirst().value()).isEqualTo("01");
            var result = query.execute(confirmed, plan.plan().planHash(), 200, 8);
            assertThat(result.rows()).extracting(row -> row.get("metric_1")).containsExactly(new BigDecimal("26"));
            assertThatThrownBy(() -> query.confirm(question + "?", VERSION, SOURCE, null, access, plan.plan().planHash()))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getCode()).isEqualTo(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED.getCode()));
            source.setUpdatedAt(LocalDateTime.now());
            assertThatThrownBy(() -> query.confirm(question, VERSION, SOURCE, null, access, plan.plan().planHash()))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getCode()).isEqualTo(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED.getCode()));
            source.setUpdatedAt(null);
            verify(candidate, times(1)).propose(eq(TENANT), any());

            String conversation = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO sf_desktop_conversation(id,tenant_id,owner_id,type,title) VALUES(?,?,?,'data','查询')",
                    conversation, TENANT, USER);
            var ask = new DesktopDataService.Ask(mixed.id(), question, null, "fixture");
            var noOntologyPlan = query.prepare(question, null, SOURCE, null, datasets.queryAccess(exploratory.id()));
            assertThat(noOntologyPlan.plan().semanticVersionId()).isNull();
            assertThat(noOntologyPlan.plan().queryPath()).isEqualTo("NL2SQL");
            var draft = datasets.ask(conversation, ask);
            assertThat(datasets.ask(conversation, ask).planHash()).isEqualTo(draft.planHash());
            assertThat(draft.plan().queryPath()).isEqualTo("NL2SQL");
            assertThat(draft.plan().columns().getFirst().label()).isEqualTo("units（总和）");
            var desktopResult = datasets.execute(conversation, draft.id(), draft.planHash());
            assertThat(desktopResult.rows()).hasSize(1);
            verify(candidate, times(3)).propose(eq(TENANT), any());
            when(authorization.hasPermission("semantic:query:explore")).thenReturn(false);
            assertThat(datasets.history(conversation, 1, 20)).isEmpty();
            assertThatThrownBy(() -> datasets.result(desktopResult.id())).isInstanceOf(BusinessException.class);

            var newKeys = redis.keys("semantic:exploration:*");
            newKeys.removeAll(originalKeys);
            assertThat(newKeys).hasSize(2);
            for (String key : newKeys) {
                assertThat(redis.getExpire(key, TimeUnit.SECONDS)).isBetween(1L, 600L);
                redis.delete(key);
            }
            when(authorization.hasPermission("semantic:query:explore")).thenReturn(true);
            assertThatThrownBy(() -> query.confirm(question, VERSION, SOURCE, null, datasets.queryAccess(mixed.id()), plan.plan().planHash()))
                    .isInstanceOfSatisfying(BusinessException.class, error ->
                            assertThat(error.getCode()).isEqualTo(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED.getCode()));
            verify(candidate, times(3)).propose(eq(TENANT), any());
        } finally {
            var keys = redis.keys("semantic:exploration:*");
            keys.removeAll(originalKeys);
            redis.delete(keys);
            factory.destroy();
        }
    }

    private DatabaseSchema schema(String qualifiedTable) {
        String namespace = qualifiedTable.substring(0, qualifiedTable.indexOf('.'));
        var object = new SchemaObject(namespace, "orders", SchemaObjectType.TABLE, null, null, null,
                List.of(new SchemaField("amount", "numeric", false, null, Map.of()),
                        new SchemaField("state", "text", false, null, Map.of()),
                        new SchemaField("created_at", "timestamptz", false, null, Map.of()),
                        new SchemaField("code", "text", false, null, Map.of()),
                        new SchemaField("units", "integer", false, null, Map.of())),
                List.of(), List.of(), List.of());
        var baseline = new SchemaObject(namespace, "approved_revenue", SchemaObjectType.VIEW, null, null, null,
                List.of(new SchemaField("revenue", "numeric", true, null, Map.of())), List.of(), List.of(), List.of());
        var grouped = new SchemaObject(namespace, "approved_daily_revenue", SchemaObjectType.VIEW, null, null, null,
                List.of(new SchemaField("revenue", "numeric", true, null, Map.of()),
                        new SchemaField("group_key", "text", true, null, Map.of()),
                        new SchemaField("event_at", "timestamptz", true, null, Map.of())), List.of(), List.of(), List.of());
        return new DatabaseSchema("postgresql", null, namespace, List.of(object, baseline, grouped), Set.of(), List.of());
    }

    private DatasetDefinition definition(String table, List<String> fields) {
        return new DatasetDefinition("订单", SOURCE, VERSION, table, fields, true, 0L);
    }

    private void seed(JdbcTemplate jdbc, ObjectMapper json, PublishedQueryProfile profile) throws Exception {
        jdbc.execute(Files.readString(Path.of("..", "..", "sql", "54_semantic_catalog.sql")));
        jdbc.execute(Files.readString(Path.of("..", "..", "sql", "66_desktop_workspace.sql")));
        jdbc.execute(Files.readString(Path.of("..", "..", "document", "sql", "ddl", "11-业务本体.sql")));
        jdbc.execute(Files.readString(Path.of("..", "..", "document", "sql", "migration",
                "2026-11-25-ontology-result-verification.sql")));
        jdbc.execute(Files.readString(Path.of("..", "..", "document", "sql", "migration",
                "2026-11-25-ontology-grouped-reconciliation.sql")));
        jdbc.execute(Files.readString(Path.of("..", "..", "sql", "08_table_auxiliary.sql")));
        jdbc.execute("CREATE TABLE sf_user(id varchar(64), tenant_id varchar(64), status varchar(10), deleted integer)");
        jdbc.update("INSERT INTO sf_user VALUES (?, ?, 'active', 0)", USER, TENANT);
        String model = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sf_semantic_model(id,tenant_id,name,domain) VALUES(?::uuid,?::uuid,'订单','销售')",
                model, TENANT);
        jdbc.update("""
                INSERT INTO sf_semantic_version(id,tenant_id,model_id,version_no,graph_iri,status,checksum)
                VALUES(?::uuid,?::uuid,?::uuid,1,'urn:ontology:orders','published',?)
                """, VERSION, TENANT, model, AUDIT_CHECKSUM);
        jdbc.update("""
                INSERT INTO sf_ontology_configuration_artifact(tenant_id,version_id,revision,configuration_json,
                    ontology_turtle,shapes_turtle,published_checksum,query_profile_json)
                VALUES(?::uuid,?::uuid,2,'{}'::jsonb,'','','' || ?,?::jsonb)
                """, TENANT, VERSION, AUDIT_CHECKSUM, json.writeValueAsString(profile));
        jdbc.execute("CREATE TABLE orders(amount numeric, state text, created_at timestamp with time zone, code text DEFAULT '01', units integer DEFAULT 4)");
        jdbc.execute("""
                INSERT INTO orders VALUES
                (99.00, '01', '2026-11-23T15:59:59Z'),
                (10.25, '01', '2026-11-23T16:00:00Z'),
                (3.50, '01', '2026-11-24T16:00:00Z'),
                (5.00, '02', '2026-11-24T16:00:00Z'),
                (11.00, '01', '2026-11-25T16:00:00Z')
                """);
        jdbc.update("UPDATE orders SET units = 10 WHERE amount = 99.00");
        jdbc.execute("CREATE VIEW approved_revenue WITH (security_invoker=true) AS SELECT SUM(amount) AS revenue FROM orders");
        jdbc.execute("CREATE TABLE finance_orders(revenue numeric, group_key text, event_at timestamptz)");
        jdbc.execute("CREATE VIEW approved_daily_revenue WITH (security_invoker=true) AS SELECT * FROM finance_orders");
    }

    private PublishedQueryProfile profile(String table) {
        return new PublishedQueryProfile(SOURCE, SNAPSHOT, "fixture", "postgresql", table, "Asia/Shanghai",
                List.of(new PublishedQueryProfile.Measure(
                                "amount", "urn:amount", "amount", "金额", "amount", "decimal", "numeric", "sum"),
                        new PublishedQueryProfile.Measure(
                                "amount", "urn:amount", "amount", "金额", "amount", "decimal", "numeric", "avg")),
                List.of(new PublishedQueryProfile.Dimension("state", "urn:state", "state", "状态", "state", "string", "text")),
                List.of(new PublishedQueryProfile.Filter("state", "urn:state", "state", "状态", "state", "string",
                        "text", List.of(new PublishedQueryProfile.Value("正常", "01"),
                                new PublishedQueryProfile.Value("异常", "02")))),
                new PublishedQueryProfile.Time("time", "urn:time", "time", "创建时间", "created_at", "dateTime",
                        "timestamp with time zone", List.of("day", "week", "month")));
    }

    private McpServer source() {
        var server = new McpServer();
        server.setId(SOURCE);
        server.setTenantId(TENANT);
        server.setConnectionConfig(Map.of("databaseType", "postgresql", "readOnly", true, "queryToolName", "query"));
        server.setTools(List.of(Map.of("name", "query")));
        return server;
    }
}
