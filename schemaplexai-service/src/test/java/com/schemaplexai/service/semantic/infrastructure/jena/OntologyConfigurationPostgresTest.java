package com.schemaplexai.service.semantic.infrastructure.jena;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.enums.OntologyReleasePurpose;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.semantic.OntologyConfigurationArtifactMapper;
import com.schemaplexai.dao.mapper.semantic.SchemaSnapshotMapper;
import com.schemaplexai.dao.mapper.semantic.SemanticModelMapper;
import com.schemaplexai.dao.mapper.semantic.SemanticVersionMapper;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.desktop.DesktopDataService;
import com.schemaplexai.service.desktop.DesktopDataService.Dataset;
import com.schemaplexai.service.desktop.DesktopDataService.DatasetDefinition;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.application.orchestration.OntologyConfigurationApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.OntologyGraphApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SchemaScanApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticPublishApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticVersionApplicationService;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticModel;
import com.schemaplexai.service.semantic.domain.model.ontology.GraphQuery;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyQueryProfile;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Concept;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Property;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Relationship;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.port.OntologyStorePort;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSessionFactory;
import com.schemaplexai.service.semantic.domain.port.SemanticPublishPort;
import com.schemaplexai.service.semantic.infrastructure.database.JdbcOntologyScopeRepository;
import com.schemaplexai.service.semantic.infrastructure.database.MybatisOntologyConfigurationStore;
import com.schemaplexai.service.semantic.infrastructure.database.MybatisSchemaSnapshotRepository;
import com.schemaplexai.service.semantic.infrastructure.database.MybatisSemanticModelRepository;
import com.schemaplexai.service.semantic.infrastructure.database.MybatisSemanticVersionRepository;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doCallRealMethod;

class OntologyConfigurationPostgresTest {

    private static final String TENANT = UUID.randomUUID().toString();
    private static final String MODEL = UUID.randomUUID().toString();

    @Test
    void persistsAndPublishesWithAtomicRollbackWhileTdb2IsUnused() throws Exception {
        String url = System.getenv("ONTOLOGY_PG_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "需设置隔离的 PostgreSQL 集成测试库");
        String user = System.getenv().getOrDefault("ONTOLOGY_PG_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("ONTOLOGY_PG_TEST_PASSWORD", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "ontology_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            String schemaUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
            var dataSource = new DriverManagerDataSource(schemaUrl, user, password);
            var jdbc = new JdbcTemplate(dataSource);
            String legacyModel = UUID.randomUUID().toString();
            String legacyVersion = UUID.randomUUID().toString();
            String legacySnapshot = UUID.randomUUID().toString();
            String legacyChecksum = "sha256:" + "e".repeat(64);
            jdbc.execute(Files.readString(Path.of("..", "..", "sql", "54_semantic_catalog.sql")));
            seedLegacy(jdbc, legacyModel, legacyVersion, legacySnapshot, legacyChecksum);
            var ddl = Files.readString(Path.of("..", "..", "document", "sql", "ddl", "11-业务本体.sql"));
            jdbc.execute(ddl);
            jdbc.execute(ddl);
            SqlSessionTemplate session = mybatis(dataSource);
            var objectMapper = new ObjectMapper().findAndRegisterModules();
            var modelRepository = new MybatisSemanticModelRepository(session.getMapper(SemanticModelMapper.class));
            var versionRepository = new MybatisSemanticVersionRepository(session.getMapper(SemanticVersionMapper.class));
            var snapshots = new MybatisSchemaSnapshotRepository(session.getMapper(SchemaSnapshotMapper.class), objectMapper);
            var store = spy(new MybatisOntologyConfigurationStore(
                    session.getMapper(OntologyConfigurationArtifactMapper.class), objectMapper));
            SecurityUtil.setCurrentTenantId(TENANT);
            SecurityUtil.setCurrentUserId(UUID.randomUUID().toString());
            var oldVersion = versionRepository.findByTenantAndId(TENANT, legacyVersion).orElseThrow();
            assertThat(oldVersion.getChecksum()).isEqualTo(legacyChecksum);
            assertThat(oldVersion.getRevision()).isEqualTo(4);
            assertThat(oldVersion.getSourceSnapshotId()).isEqualTo(legacySnapshot);
            assertThat(modelRepository.findByTenantAndId(TENANT, legacyModel)).get()
                    .extracting(SemanticModel::getActiveVersionId).isEqualTo(legacyVersion);
            assertThat(store.published(TENANT, legacyVersion)).isEmpty();
            var scopes = new JdbcOntologyScopeRepository(jdbc);
            var scope = scopes.createOrGet(TENANT, "客服系统", "售后服务");
            assertThat(scopes.createOrGet(TENANT, "客服系统", "售后服务")).isEqualTo(scope);
            assertThat(scopes.find(UUID.randomUUID().toString(), scope.id())).isEmpty();
            modelRepository.insert(SemanticModel.create(MODEL, TENANT, "工单本体", "售后服务", null, scope.id()));
            assertThat(modelRepository.findByTenantAndId(TENANT, MODEL)).get()
                    .extracting(SemanticModel::getScopeId).isEqualTo(scope.id());

            var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var iri = new SemanticGraphIriFactory();
            var properties = new SemanticStoreProperties();
            var tdb = mock(SemanticDatasetManager.class);
            var oldGraph = mock(OntologyStorePort.class);
            var legacyPublisher = mock(SemanticPublishPort.class);
            var auth = mock(AuthorizationService.class);
            var statements = new JenaOntologyStatementMapper();
            var graphReader = new JenaOntologyStoreAdapter(
                    tdb, new TenantDatasetViewFactory(iri), iri, properties, statements);
            var jena = new JenaOntologyArtifactCompiler(
                    statements, graphReader, new RestrictedRdfsMaterializer(), properties, objectMapper);
            var scan = new SchemaScanApplicationService(mock(SchemaMetadataSessionFactory.class),
                    snapshots, List.of(), mock(DatabaseSourceService.class));
            var versions = new SemanticVersionApplicationService(modelRepository, versionRepository,
                    snapshots, iri, scan, store, jena);
            var configuration = new OntologyConfigurationApplicationService(versions, modelRepository,
                    versionRepository, snapshots, scan, store, jena, oldGraph, auth);
            var graph = new OntologyGraphApplicationService(
                    versionRepository, oldGraph, store, jena, auth, mock(DatabaseSourceService.class));
            var publish = new SemanticPublishApplicationService(modelRepository, versionRepository,
                    legacyPublisher, store, jena, configuration);

            String id = tx.execute(status -> versions.createConfigurationDraft(MODEL, null)).getId();
            assertThat(store.published(TENANT, id)).contains(false);
            assertThat(tx.execute(status -> publish.validate(id, 0)).valid()).isFalse();
            assertThat(tx.execute(status -> configuration.load(id)).editable()).isTrue();

            var saved = tx.execute(status -> configuration.save(id, 0, validConfiguration()));
            assertThat(saved.revision()).isEqualTo(1);
            assertThat(tx.execute(status -> configuration.load(id)).configuration()).isEqualTo(validConfiguration());
            assertThat(tx.execute(status -> configuration.artifacts(id)).shapesTurtle()).contains("minCount", "datatype");
            assertThat(graph.query(id, new GraphQuery(null, "服务", 1, 1, 30)).nodes()).isNotEmpty();
            assertThatThrownBy(() -> tx.execute(status -> configuration.save(id, 0, validConfiguration())))
                    .isInstanceOf(BusinessException.class);

            var invalid = new OntologyConfiguration(
                    List.of(new Concept("contact", "Contact", "联系人", null, "forged.table")), List.of(), List.of());
            assertThatThrownBy(() -> tx.execute(status -> configuration.save(id, 1, invalid)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("Schema 快照");
            assertThat(tx.execute(status -> configuration.load(id)).revision()).isEqualTo(1);
            assertThat(tx.execute(status -> publish.validate(id, 1)).valid()).isTrue();

            doReturn(false).when(store).freeze(anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString(), isNull());
            assertThatThrownBy(() -> tx.execute(status -> publish.publish(id, 1))).isInstanceOf(BusinessException.class);
            assertThat(versionRepository.findByTenantAndId(TENANT, id)).get()
                    .extracting(version -> version.getStatus()).isEqualTo(SemanticVersionStatus.DRAFT);
            assertThat(modelRepository.findByTenantAndId(TENANT, MODEL)).get()
                    .extracting(SemanticModel::getActiveVersionId).isNull();
            assertThat(store.published(TENANT, id)).contains(false);

            doCallRealMethod().when(store).freeze(anyString(), anyString(), anyLong(), anyLong(), anyString(), anyString(), isNull());
            var published = tx.execute(status -> publish.publish(id, 1));
            assertThat(published.getStatus()).isEqualTo(SemanticVersionStatus.PUBLISHED);
            assertThat(modelRepository.findByTenantAndId(TENANT, MODEL)).get()
                    .extracting(SemanticModel::getActiveVersionId).isEqualTo(id);
            var released = configuration.getReleasedForContext(id);
            assertThat(released.checksum()).isEqualTo(published.getChecksum());
            assertThat(released.configuration()).isEqualTo(validConfiguration());
            var immutable = store.find(TENANT, id).orElseThrow();
            assertThat(immutable.inferredTurtle()).contains("concept:ticket", "concept:item");
            assertThat(store.saveDraft(TENANT, id, new OntologyConfigurationArtifact(
                    immutable.revision() + 1, immutable.configuration(), "changed", "", null, null))).isFalse();
            assertThat(tx.execute(status -> configuration.load(id)).readiness().queryReady()).isFalse();
            assertThatThrownBy(() -> tx.execute(status -> configuration.save(id, published.getRevision(), validConfiguration())))
                    .isInstanceOf(BusinessException.class);

            String sourceId = UUID.randomUUID().toString();
            var table = new SchemaObject("support", "tickets", SchemaObjectType.TABLE, "工单", null, null,
                    List.of(new SchemaField("ticket_id", "bigint", false, "工单编号", Map.of()),
                            new SchemaField("region", "text", false, null, Map.of()),
                            new SchemaField("amount", "numeric", false, null, Map.of()),
                            new SchemaField("created_at", "timestamp with time zone", false, null, Map.of())),
                    List.of("ticket_id"), List.of(), List.of());
            var schemaDefinition = new DatabaseSchema("postgresql", "work", "support", List.of(table), Set.of(), List.of());
            var snapshot = SchemaSnapshot.create(UUID.randomUUID().toString(), TENANT, sourceId,
                    "fixture", schemaDefinition, LocalDateTime.now());
            snapshots.insert(snapshot);
            assertThat(scan.getSnapshot(sourceId, snapshot.getId()).getSchema()).isEqualTo(schemaDefinition);
            String mappedId = tx.execute(status -> versions.createConfigurationDraft(MODEL, snapshot.getId())).getId();
            var mapped = new OntologyConfiguration(
                    List.of(new Concept("ticket", "Ticket", "服务工单", null, table.key())),
                    List.of(new Property("key", "ticket", "key", "编号", "integer", true, "ticket_id"),
                            new Property("region", "ticket", "region", "区域", "string", true, "region"),
                            new Property("amount", "ticket", "amount", "金额", "decimal", true, "amount"),
                            new Property("created", "ticket", "createdAt", "创建时间", "dateTime", true, "created_at")),
                    List.of(), new OntologyQueryProfile("ticket", "Asia/Shanghai",
                            List.of(new OntologyQueryProfile.Measure("key", "count"),
                                    new OntologyQueryProfile.Measure("amount", "sum")),
                            List.of(new OntologyQueryProfile.Dimension("region")),
                            List.of(new OntologyQueryProfile.Filter("region", List.of(new OntologyQueryProfile.Value("华东", "EAST")))),
                            new OntologyQueryProfile.Time("created", List.of("day", "week"))));
            assertThat(tx.execute(status -> configuration.save(mappedId, 0, mapped)).sourceId()).isEqualTo(sourceId);
            var forgedColumn = new OntologyConfiguration(mapped.classes(),
                    List.of(new Property("key", "ticket", "key", "编号", "integer", true, "absent")), List.of());
            assertThatThrownBy(() -> tx.execute(status -> configuration.save(mappedId, 1, forgedColumn)))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("来源字段");
            assertThat(tx.execute(status -> publish.publish(mappedId, 1)).getStatus()).isEqualTo(SemanticVersionStatus.PUBLISHED);
            assertThat(store.find(TENANT, mappedId).orElseThrow().queryProfile()).isNull();
            var context = configuration.getReleasedForContext(mappedId).configuration();
            assertThat(context.classes().getFirst().sourceObject()).isNull();
            assertThat(context.properties().getFirst().sourceField()).isNull();
            var redacted = tx.execute(status -> configuration.load(mappedId));
            assertThat(redacted.sourceId()).isNull();
            assertThat(redacted.sourceSnapshotId()).isNull();
            assertThat(redacted.configuration()).isEqualTo(context);
            when(auth.hasPermission("database:source:view")).thenReturn(true);
            assertThat(tx.execute(status -> configuration.load(mappedId)).configuration()).isEqualTo(mapped);

            String queryId = tx.execute(status -> versions.createConfigurationDraft(MODEL, snapshot.getId())).getId();
            assertThat(tx.execute(status -> publish.validate(queryId, 0, OntologyReleasePurpose.QUERY)).valid()).isFalse();
            assertThat(tx.execute(status -> configuration.save(queryId, 0, mapped)).readiness().queryReady()).isFalse();
            assertThat(tx.execute(status -> publish.validate(queryId, 1, OntologyReleasePurpose.QUERY)).valid()).isTrue();
            var queryVersion = tx.execute(status -> publish.publish(queryId, 1, OntologyReleasePurpose.QUERY));
            assertThat(queryVersion.getStatus()).isEqualTo(SemanticVersionStatus.PUBLISHED);
            assertThat(publish.releaseReadiness(queryVersion).queryReady()).isTrue();
            var queryProfile = store.find(TENANT, queryId).orElseThrow().queryProfile();
            assertThat(queryProfile.sourceSnapshotId()).isEqualTo(snapshot.getId());
            assertThat(queryProfile.physicalObject()).isEqualTo("support.tickets");
            assertThat(queryProfile.measures().getLast().physicalField()).isEqualTo("amount");
            assertThat(queryProfile.filters().getFirst().values().getFirst().value()).isEqualTo("EAST");
            assertThat(queryProfile.time().physicalType()).isEqualTo("timestamp with time zone");
            assertThat(configuration.getReleasedForContext(queryId).configuration().queryProfile()).isNull();
            assertDesktopReadiness(jdbc, objectMapper, legacyVersion, mappedId, queryId, sourceId);

            SecurityUtil.setCurrentTenantId(UUID.randomUUID().toString());
            assertThatThrownBy(() -> configuration.getReleasedForContext(id)).isInstanceOf(BusinessException.class);
            verifyNoInteractions(tdb, oldGraph, legacyPublisher);
        } finally {
            SecurityUtil.clear();
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    private void seedLegacy(JdbcTemplate jdbc, String model, String version, String snapshot, String checksum) {
        jdbc.update("""
                INSERT INTO sf_semantic_model(id,tenant_id,name,domain,revision)
                VALUES(?::uuid,?::uuid,'历史模型','历史域',2)
                """, model, TENANT);
        jdbc.update("""
                INSERT INTO sf_schema_snapshot(id,tenant_id,source_id,fingerprint,schema_json)
                VALUES(?::uuid,?::uuid,?::uuid,'legacy','{}')
                """, snapshot, TENANT, UUID.randomUUID().toString());
        jdbc.update("""
                INSERT INTO sf_semantic_version
                    (id,tenant_id,model_id,version_no,graph_iri,source_snapshot_id,status,revision,checksum)
                VALUES(?::uuid,?::uuid,?::uuid,1,'urn:legacy:asserted',?::uuid,'published',4,?)
                """, version, TENANT, model, snapshot, checksum);
        jdbc.update("UPDATE sf_semantic_model SET active_version_id=?::uuid WHERE id=?::uuid", version, model);
    }

    private void assertDesktopReadiness(JdbcTemplate jdbc, ObjectMapper json, String legacyVersion,
                                        String contextOnlyVersion, String queryVersion, String sourceId) throws Exception {
        jdbc.execute(Files.readString(Path.of("..", "..", "sql", "66_desktop_workspace.sql")));
        var authorization = mock(AuthorizationService.class);
        when(authorization.hasPermission("database:source:manage")).thenReturn(true);
        when(authorization.hasPermission("semantic:query:interpret")).thenReturn(true);
        when(authorization.hasPermission("semantic:query:execute")).thenReturn(true);
        var service = new DesktopDataService(jdbc, json, null, authorization, mock(DatabaseSourceService.class), null);
        var contextOnly = new DatasetDefinition("仅上下文", sourceId, contextOnlyVersion, "support.tickets",
                List.of("ticket_id"), true, 0L);
        assertThatThrownBy(() -> service.saveDataset(null, contextOnly))
                .isInstanceOf(BusinessException.class).hasMessageContaining("具备查询能力");
        var legacy = service.saveDataset(null, new DatasetDefinition("历史数据集", sourceId, legacyVersion,
                "support.tickets", List.of("ticket_id"), true, 0L));
        var queryable = service.saveDataset(null, new DatasetDefinition("指标数据集", sourceId, queryVersion,
                "support.tickets", List.of("ticket_id", "region"), true, 0L));
        assertThatThrownBy(() -> service.saveDataset(null, new DatasetDefinition("无效字段", sourceId, queryVersion,
                "support.tickets", List.of("ticket_id", "private_field"), true, 0L))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveDataset(null, new DatasetDefinition("无效来源", UUID.randomUUID().toString(),
                queryVersion, "support.tickets", List.of("ticket_id"), true, 0L))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.saveDataset(null, new DatasetDefinition("无效物理对象", sourceId, queryVersion,
                "support.accounts", List.of("ticket_id"), true, 0L))).isInstanceOf(BusinessException.class);
        var invalidDatasetId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sf_desktop_dataset(id,tenant_id,name,source_id,version_id,physical_object,allowed_fields)
                VALUES(?,?,?,?,?,'support.tickets',ARRAY['ticket_id'])
                """, invalidDatasetId, TENANT, "已存在的上下文数据集", sourceId, contextOnlyVersion);
        for (String datasetId : List.of(legacy.id(), invalidDatasetId, queryable.id())) {
            jdbc.update("INSERT INTO sf_desktop_dataset_grant(tenant_id,dataset_id,user_id) VALUES(?,?,?)",
                    TENANT, datasetId, SecurityUtil.getCurrentUserId());
        }
        assertThat(service.catalog()).extracting(Dataset::id).containsExactlyInAnyOrder(legacy.id(), queryable.id());
    }

    private OntologyConfiguration validConfiguration() {
        return new OntologyConfiguration(
                List.of(new Concept("ticket", "Ticket", "服务工单", null, null),
                        new Concept("request", "Request", "请求", null, null),
                        new Concept("item", "Item", "事项", null, null)),
                List.of(new Property("title", "ticket", "title", "标题", "string", true, null)),
                List.of(new Relationship("child", "ticket", "request", "ticketRequest", "工单继承请求", "SUBCLASS"),
                        new Relationship("parent", "request", "item", "requestItem", "请求继承事项", "SUBCLASS")));
    }

    private SqlSessionTemplate mybatis(DriverManagerDataSource dataSource) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMappers("com.schemaplexai.dao.mapper.semantic");
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(SecurityUtil.getCurrentTenantId());
            }
        }));
        factory.setPlugins(interceptor);
        return new SqlSessionTemplate(factory.getObject());
    }
}
