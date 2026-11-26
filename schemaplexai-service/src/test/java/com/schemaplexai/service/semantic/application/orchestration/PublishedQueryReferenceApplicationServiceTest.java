package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.vo.database.DatabaseSourceVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService.DimensionMapping;
import com.schemaplexai.service.semantic.application.orchestration.PublishedQueryReferenceApplicationService.Register;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.SemanticVersion;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import com.schemaplexai.service.semantic.domain.model.query.QueryCalendar;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.repository.PublishedQueryReferenceRepository;
import com.schemaplexai.service.semantic.domain.repository.PublishedQueryReferenceRepository.Dataset;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublishedQueryReferenceApplicationServiceTest {

    private static final String TENANT = "tenant";
    private static final String USER = "owner";
    private static final String DATASET = "ds";
    private static final String SOURCE = "source";
    private static final String VERSION = "version";
    private static final String SNAPSHOT = "snapshot";
    private static final String OBJECT = "reporting.orders";
    private static final String MEASURE_IRI = "urn:spx:tenant:semantic:property:amount";
    private static final LocalDateTime SOURCE_TIME = LocalDateTime.of(2026, 11, 25, 8, 0);

    private final PublishedQueryReferenceRepository references = mock(PublishedQueryReferenceRepository.class);
    private final SemanticVersionRepository versions = mock(SemanticVersionRepository.class);
    private final OntologyConfigurationStorePort artifacts = mock(OntologyConfigurationStorePort.class);
    private final SchemaSnapshotRepository snapshots = mock(SchemaSnapshotRepository.class);
    private final DatabaseSourceService sources = mock(DatabaseSourceService.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);
    private final PublishedQueryReferenceApplicationService service = new PublishedQueryReferenceApplicationService(
            references, versions, artifacts, snapshots, sources, authorization);

    @BeforeEach
    void setUp() {
        SecurityUtil.setCurrentTenantId(TENANT);
        SecurityUtil.setCurrentUserId(USER);
    }

    @AfterEach
    void clearActor() {
        SecurityUtil.clear();
    }

    @Test
    void registersFixedReferenceIdempotentlyAndChangesBindingOnEdit() {
        setupRelease(List.of("amount"));
        var reference = service.register(DATASET, command("0.0100", "财务对账"));
        assertThat(reference.measureIri()).isEqualTo(MEASURE_IRI);
        assertThat(reference.measureColumn()).isEqualTo("amount");
        assertThat(reference.releaseChecksum()).isEqualTo("checksum");
        assertThat(reference.schemaSnapshotId()).isEqualTo(SNAPSHOT);
        assertThat(reference.sourceRevision()).isEqualTo(SOURCE_TIME.toString());
        assertThat(reference.tolerance().toPlainString()).isEqualTo("0.01");
        verify(references).save(TENANT, USER, reference);

        when(references.find(TENANT, DATASET)).thenReturn(Optional.of(reference));
        assertThat(service.register(DATASET, command("0.01", "财务对账"))).isEqualTo(reference);
        var changed = service.register(DATASET, command("0.01", "月结对账"));
        assertThat(changed.bindingKey()).isNotEqualTo(reference.bindingKey());
        verify(references, times(2)).save(eq(TENANT), eq(USER), any());
        service.remove(DATASET);
        verify(references).delete(TENANT, DATASET);
    }

    @Test
    void deniesCrossTenantOrMissingManagementPermission() {
        assertThatThrownBy(() -> service.get(DATASET)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));
        verifyNoInteractions(references);

        when(authorization.hasPermission("database:source:manage")).thenReturn(true);
        SecurityUtil.setCurrentTenantId("other");
        assertThatThrownBy(() -> service.remove(DATASET)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(ResultCode.NOT_FOUND.getCode()));
        verify(references).findDataset("other", DATASET, true);
        verify(references, never()).delete(anyString(), anyString());
    }

    @Test
    void rejectsUnapprovedMeasureTableTextColumnAndInvalidTolerance() {
        setupRelease(List.of("other_field"));
        assertThatThrownBy(() -> service.register(DATASET, command(null, "参考")))
                .hasMessageContaining("已授权的发布指标");
        setupRelease(List.of("amount"));
        assertThatThrownBy(() -> service.register(DATASET,
                new Register("amount", "sum", OBJECT, "amount", null, "参考"))).hasMessageContaining("与查询对象不同");
        assertThatThrownBy(() -> service.register(DATASET,
                new Register("amount", "sum", "reporting.other_table", "amount", null, "参考"))).hasMessageContaining("普通参考视图");
        assertThatThrownBy(() -> service.register(DATASET,
                new Register("amount", "sum", "reporting.finance_reference", "comment", null, "参考"))).hasMessageContaining("精确小数列");
        for (var invalid : List.of("-1", "2e-5", "0.1234567890123456789", "100000000000000000000")) {
            assertThatThrownBy(() -> service.register(DATASET, command(invalid, "参考"))).hasMessageContaining("非负十进制");
        }
        verify(references, never()).save(anyString(), anyString(), any());
    }

    @Test
    void lookupUsesCurrentPublishedSingletonAndConnectorRatherThanDatasetGrantRevision() {
        var source = setupRelease(List.of("amount"));
        var reference = service.register(DATASET, command(null, "参考"));
        when(references.find(TENANT, DATASET)).thenReturn(Optional.of(reference));

        assertThat(service.findFor(access(0), intent("sum", List.of()))).contains(reference);
        assertThat(service.findFor(access(5), intent("sum", List.of()))).contains(reference);
        assertThat(service.findFor(access(5), intent("avg", List.of()))).isEmpty();
        var filter = new QueryFilter(MEASURE_IRI, "订单金额", "eq", "10", OBJECT, "amount");
        assertThat(service.findFor(access(5), intent("sum", List.of(filter)))).isEmpty();
        assertThatThrownBy(() -> service.findFor(new SemanticQueryDatasetAccess(DATASET, 5, VERSION, SOURCE, OBJECT,
                List.of("other_field")), intent("sum", List.of()))).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(ResultCode.FORBIDDEN.getCode()));

        source.setUpdatedAt(SOURCE_TIME.plusMinutes(1));
        assertThat(service.findFor(access(5), intent("sum", List.of()))).isEmpty();
        assertThat(service.register(DATASET, command(null, "参考")).bindingKey()).isNotEqualTo(reference.bindingKey());
        source.setReadOnly(false);
        assertThatThrownBy(() -> service.register(DATASET, command(null, "参考"))).hasMessageContaining("只读");
    }

    @Test
    void registersTypedDimensionAndPinnedTimeReferenceColumns() {
        setupRelease(List.of("amount", "status", "created_at"));
        var registered = service.register(DATASET, new Register("amount", "sum", "reporting.finance_reference",
                "total", "0.25", "按状态对账",
                List.of(new DimensionMapping("status", "status")), "created_at"));

        assertThat(registered.dimensions()).containsExactly(new PublishedQueryReference.Dimension(
                "status", "urn:spx:tenant:semantic:property:status", "status", "string", "text", "status"));
        assertThat(registered.time()).isEqualTo(new PublishedQueryReference.Time(
                "created_at", "urn:spx:tenant:semantic:property:created_at", "created_at", "dateTime",
                "timestamp with time zone", "UTC", "created_at"));
    }

    @Test
    void rejectsUnknownDuplicateOrTypeIncompatibleMappingAndUnauthorizedTimeColumn() {
        setupRelease(List.of("amount", "status", "created_at"));
        assertThatThrownBy(() -> service.register(DATASET, new Register("amount", "sum",
                "reporting.finance_reference", "total", null, "参考",
                List.of(new DimensionMapping("unknown", "status")), null)))
                .hasMessageContaining("来自当前发布版本的维度");
        assertThatThrownBy(() -> service.register(DATASET, new Register("amount", "sum",
                "reporting.finance_reference", "total", null, "参考",
                List.of(new DimensionMapping("status", "total")), null)))
                .hasMessageContaining("类型必须与已发布维度一致");
        assertThatThrownBy(() -> service.register(DATASET, new Register("amount", "sum",
                "reporting.finance_reference", "total", null, "参考",
                List.of(new DimensionMapping("status", "status"),
                        new DimensionMapping("status", "status")), null)))
                .hasMessageContaining("重复、缺失");
        assertThatThrownBy(() -> service.register(DATASET, new Register("amount", "sum",
                "reporting.finance_reference", "total", null, "参考", List.of(),
                "status")))
                .hasMessageContaining("参考日期列类型必须与已发布");
        setupRelease(List.of("amount", "status"));
        assertThatThrownBy(() -> service.register(DATASET, new Register("amount", "sum",
                "reporting.finance_reference", "total", null, "参考", List.of(),
                "created_at")))
                .hasMessageContaining("已发布并授权的本体时间属性");
    }

    @Test
    void onlyMatchesExactPublishedDimensionsAndFrozenTimeBinding() {
        setupRelease(List.of("amount", "status", "created_at"));
        var reference = service.register(DATASET, new Register("amount", "sum", "reporting.finance_reference",
                "total", null, "按状态日期对账",
                List.of(new PublishedQueryReferenceApplicationService.DimensionMapping("status", "status")),
                "created_at"));
        when(references.find(TENANT, DATASET)).thenReturn(Optional.of(reference));
        var dimension = new QueryDimension("urn:spx:tenant:semantic:property:status", "状态", null,
                OBJECT, "status");
        var time = new QueryTimeRange("urn:spx:tenant:semantic:property:created_at", null,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                OBJECT, "created_at", new QueryCalendar("UTC", "timestamp with time zone"));
        assertThat(service.findFor(access(3), intent("sum", List.of(), List.of(dimension), time))).contains(reference);
        assertThat(service.findFor(access(3), intent("sum", List.of(), List.of(), time))).isEmpty();
        var relative = new QueryTimeRange("urn:spx:tenant:semantic:property:created_at", 7, null, null,
                OBJECT, "created_at", new QueryCalendar("UTC", "timestamp with time zone"));
        assertThat(service.findFor(access(3), intent("sum", List.of(), List.of(dimension), relative))).isEmpty();
        var filter = new QueryFilter(MEASURE_IRI, "金额", "eq", "10", OBJECT, "amount");
        assertThat(service.findFor(access(3), intent("sum", List.of(filter), List.of(dimension), time))).isEmpty();
        var otherDimension = new QueryDimension("urn:other:dimension", "其他", null, OBJECT, "status");
        assertThat(service.findFor(access(3), intent("sum", List.of(), List.of(otherDimension), time))).isEmpty();
    }

    private DatabaseSourceVO setupRelease(List<String> allowedFields) {
        when(authorization.hasPermission("database:source:manage")).thenReturn(true);
        when(references.findDataset(TENANT, DATASET, true)).thenReturn(Optional.of(
                new Dataset(DATASET, SOURCE, VERSION, OBJECT, allowedFields, true)));
        var version = SemanticVersion.restore(VERSION, TENANT, "model", 1, "urn:graph", SNAPSHOT,
                SemanticVersionStatus.PUBLISHED, "checksum", 12, null, SOURCE_TIME, 1);
        when(versions.findByTenantAndId(TENANT, VERSION)).thenReturn(Optional.of(version));
        var profile = new PublishedQueryProfile(SOURCE, SNAPSHOT, "fingerprint", "postgresql", OBJECT, "UTC",
                List.of(new PublishedQueryProfile.Measure("amount", MEASURE_IRI, "amount", "订单金额",
                        "amount", "decimal", "numeric", "sum")),
                List.of(new PublishedQueryProfile.Dimension("status",
                                "urn:spx:tenant:semantic:property:status", "status", "状态",
                                "status", "string", "text")),
                List.of(), new PublishedQueryProfile.Time("created_at",
                        "urn:spx:tenant:semantic:property:created_at", "created_at", "创建时间",
                        "created_at", "dateTime", "timestamp with time zone", List.of("day", "week", "month")));
        when(artifacts.find(TENANT, VERSION)).thenReturn(Optional.of(
                new OntologyConfigurationArtifact(1, null, "", "", "", "checksum", profile)));
        var field = new SchemaField("amount", "numeric", false, null, null);
        var status = new SchemaField("status", "text", true, null, null);
        var createdAt = new SchemaField("created_at", "timestamp with time zone", true, null, null);
        var schema = new DatabaseSchema("postgresql", "fixtures", "reporting", List.of(
                object("orders", SchemaObjectType.TABLE, field, status, createdAt),
                object("other_table", SchemaObjectType.TABLE, field),
                object("finance_reference", SchemaObjectType.VIEW,
                        new SchemaField("total", "numeric", false, null, null),
                        status, createdAt, new SchemaField("comment", "text", true, null, null))), Set.of(), List.of());
        when(snapshots.findByTenantAndId(TENANT, SNAPSHOT)).thenReturn(Optional.of(
                SchemaSnapshot.create(SNAPSHOT, TENANT, SOURCE, "fingerprint", schema, SOURCE_TIME)));
        var source = new DatabaseSourceVO();
        source.setDatabaseType("postgresql");
        source.setReadOnly(true);
        source.setUpdatedAt(SOURCE_TIME);
        when(sources.getById(SOURCE)).thenReturn(source);
        return source;
    }

    private SchemaObject object(String name, SchemaObjectType type, SchemaField... fields) {
        return new SchemaObject("reporting", name, type, null, null, null, List.of(fields),
                List.of(), List.of(), List.of());
    }

    private Register command(String tolerance, String label) {
        return new Register("amount", "sum", "reporting.finance_reference", "total", tolerance, label);
    }

    private QueryIntent intent(String aggregation, List<QueryFilter> filters) {
        return intent(aggregation, filters, List.of(), null);
    }

    private QueryIntent intent(String aggregation, List<QueryFilter> filters, List<QueryDimension> dimensions,
                               QueryTimeRange range) {
        return new QueryIntent(VERSION, SOURCE, List.of(new QueryMetric(MEASURE_IRI, "订单金额", aggregation, OBJECT,
                "amount")), dimensions, filters, range, 200, new QueryOutput("table", ""), "published scope");
    }

    private SemanticQueryDatasetAccess access(long revision) {
        return new SemanticQueryDatasetAccess(DATASET, revision, VERSION, SOURCE, OBJECT,
                List.of("amount", "status", "created_at"));
    }
}
