package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.vo.database.DatabaseSourceVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExploratoryQueryScopeApplicationServiceTest {

    private static final String TENANT = "tenant";
    private static final String SOURCE = "source";
    private static final String SNAPSHOT = "snapshot";
    private static final String VERSION = "version";
    private static final String OBJECT = "reporting.orders";
    private static final LocalDateTime REVISION = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final SchemaField PUBLISHED = new SchemaField("paid", "decimal(18,2)", false, null, null);
    private static final SchemaField INDEPENDENT = new SchemaField("installments", "tinyint unsigned", false, null, null);

    private final SchemaSnapshotRepository snapshots = mock(SchemaSnapshotRepository.class);
    private final SemanticVersionRepository versions = mock(SemanticVersionRepository.class);
    private final OntologyConfigurationStorePort artifacts = mock(OntologyConfigurationStorePort.class);
    private final DatabaseSourceService sources = mock(DatabaseSourceService.class);
    private final ExploratoryQueryScopeApplicationService service =
            new ExploratoryQueryScopeApplicationService(snapshots, versions, artifacts, sources);
    private final DatabaseSourceVO source = new DatabaseSourceVO();

    @BeforeEach
    void setup() {
        SecurityUtil.setCurrentTenantId(TENANT);
        source.setDatabaseType("mysql");
        source.setReadOnly(true);
        source.setUpdatedAt(REVISION);
        when(sources.getById(SOURCE)).thenReturn(source);
    }

    @AfterEach
    void clearTenant() {
        SecurityUtil.clear();
    }

    @Test
    void permitsOnlyIndependentWhitelistedColumnsFromThePinnedMysqlObject() {
        snapshot("mysql", SOURCE, List.of());
        var version = SemanticVersion.restore(VERSION, TENANT, "model", 1, "urn:graph", SNAPSHOT,
                SemanticVersionStatus.PUBLISHED, "checksum", 1, null, REVISION, 1);
        when(versions.findByTenantAndId(TENANT, VERSION)).thenReturn(Optional.of(version));
        var profile = new PublishedQueryProfile(SOURCE, SNAPSHOT, "fingerprint", "mysql", OBJECT, "UTC",
                List.of(new PublishedQueryProfile.Measure("paid", "urn:measure", "paid", "实付金额",
                        "paid", "decimal", "decimal", "sum")), List.of(), List.of(), null);
        when(artifacts.find(TENANT, VERSION)).thenReturn(Optional.of(
                new OntologyConfigurationArtifact(1, null, "", "", "", "checksum", profile)));

        var scope = service.authorize(access(VERSION, OBJECT, List.of("paid", "installments")));

        assertThat(scope.fingerprint()).isEqualTo("fingerprint");
        assertThat(scope.sourceRevision()).isEqualTo(REVISION.toString());
        assertThat(scope.fields()).containsExactly(INDEPENDENT);
        assertThatThrownBy(() -> service.authorize(access(VERSION, OBJECT, List.of("paid"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("没有可用于探索的独立字段");
    }

    @Test
    void rejectsWritableConnectionsAndMismatchedOrIncompleteSnapshots() {
        var access = access(null, OBJECT, List.of("installments"));
        source.setReadOnly(false);
        assertThatThrownBy(() -> service.authorize(access)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("只读");

        source.setReadOnly(true);
        snapshot("postgresql", SOURCE, List.of());
        assertThatThrownBy(() -> service.authorize(access)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("完整 Schema 快照");

        snapshot("mysql", "another-source", List.of());
        assertThatThrownBy(() -> service.authorize(access)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("完整 Schema 快照");

        snapshot("mysql", SOURCE, List.of("incomplete"));
        assertThatThrownBy(() -> service.authorize(access)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("完整 Schema 快照");
    }

    @Test
    void rejectsObjectsAndColumnsOutsideThePinnedSnapshot() {
        snapshot("mysql", SOURCE, List.of());
        assertThatThrownBy(() -> service.authorize(access(null, "another.orders", List.of("installments"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("对象不在所选 Schema 快照内");
        assertThatThrownBy(() -> service.authorize(access(null, OBJECT, List.of("other_field"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("字段须属于所选 Schema 对象");
    }

    private SemanticQueryDatasetAccess access(String version, String object, List<String> fields) {
        return new SemanticQueryDatasetAccess("dataset", 1, version, SOURCE, object, fields, SNAPSHOT, true);
    }

    private void snapshot(String dialect, String sourceId, List<String> warnings) {
        var object = new SchemaObject("reporting", "orders", SchemaObjectType.TABLE,
                null, null, null, List.of(PUBLISHED, INDEPENDENT), List.of(), List.of(), List.of());
        var schema = new DatabaseSchema(dialect, null, "reporting", List.of(object), Set.of(), warnings);
        when(snapshots.findByTenantAndId(TENANT, SNAPSHOT)).thenReturn(Optional.of(
                SchemaSnapshot.create(SNAPSHOT, TENANT, sourceId, "fingerprint", schema, REVISION)));
    }
}
