package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.security.AuthorizationService;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.common.SchemaSnapshotStatus;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.repository.PublishedQueryReferenceRepository;
import com.schemaplexai.service.semantic.domain.repository.PublishedQueryReferenceRepository.Dataset;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 登记和查询授权发布指标的独立参考配置。 */
@Service
@RequiredArgsConstructor
public class PublishedQueryReferenceApplicationService {

    private static final String MANAGE = "database:source:manage";
    private static final String IDENTIFIER = "[A-Za-z_][A-Za-z0-9_$]{0,62}";
    private static final Pattern COLUMN = Pattern.compile(IDENTIFIER);
    private static final Pattern OBJECT = Pattern.compile(IDENTIFIER + "\\." + IDENTIFIER);
    private static final Pattern DECIMAL = Pattern.compile("[0-9]{1,20}(?:\\.[0-9]{1,18})?");
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "numeric", "decimal", "smallint", "integer", "bigint", "int", "int2", "int4", "int8");

    private final PublishedQueryReferenceRepository references;
    private final SemanticVersionRepository versions;
    private final OntologyConfigurationStorePort artifacts;
    private final SchemaSnapshotRepository snapshots;
    private final DatabaseSourceService sources;
    private final AuthorizationService authorization;

    public record DimensionMapping(String propertyId, String referenceColumn) {
    }

    public record Register(String measurePropertyId, String aggregation, String referenceObject,
                           String referenceColumn, String tolerance, String label,
                           List<DimensionMapping> dimensions, String timeReferenceColumn) {
        public Register(String measurePropertyId, String aggregation, String referenceObject,
                        String referenceColumn, String tolerance, String label) {
            this(measurePropertyId, aggregation, referenceObject, referenceColumn, tolerance, label, List.of(), null);
        }
    }

    public Optional<PublishedQueryReference> get(String datasetId) {
        var actor = administrator();
        requireDataset(actor.tenantId(), datasetId, false);
        return references.find(actor.tenantId(), datasetId);
    }

    @Transactional
    public PublishedQueryReference register(String datasetId, Register command) {
        var actor = administrator();
        var dataset = requireDataset(actor.tenantId(), datasetId, true);
        if (!dataset.enabled() || dataset.versionId() == null) {
            throw invalid("请先启用数据集并绑定可查询的已发布本体");
        }
        var release = requireRelease(actor.tenantId(), dataset);
        var measure = release.profile().measures().stream().filter(candidate ->
                candidate.propertyId().equals(command.measurePropertyId())
                        && candidate.aggregation().equals(command.aggregation())
                        && dataset.allowedFields().contains(candidate.physicalField())).findFirst()
                .orElseThrow(() -> invalid("参考配置必须选择该数据集已授权的发布指标与聚合"));
        var view = validateView(command, dataset, release.snapshot());
        var source = schemaObject(release.snapshot(), dataset.physicalObject(), null, "固定快照中缺少查询表或视图");
        var dimensions = dimensions(command, release.profile(), dataset, source, view);
        var time = time(command, release.profile(), dataset, source, view);
        ensureDistinctColumns(command.referenceColumn(), dimensions, time);
        String sourceRevision = readOnlySourceRevision(dataset.sourceId())
                .orElseThrow(() -> invalid("独立参考需要配置为只读的 PostgreSQL 数据源"));
        var previous = references.find(actor.tenantId(), datasetId);
        String bindingKey = previous.map(PublishedQueryReference::bindingKey).orElseGet(this::newBinding);
        var proposed = reference(dataset, release, measure, command, dimensions, time, sourceRevision, bindingKey);
        if (previous.filter(proposed::equals).isPresent()) {
            return proposed;
        }
        var saved = previous.isEmpty() ? proposed
                : reference(dataset, release, measure, command, dimensions, time, sourceRevision, newBinding());
        references.save(actor.tenantId(), actor.userId(), saved);
        return saved;
    }

    @Transactional
    public void remove(String datasetId) {
        var actor = administrator();
        requireDataset(actor.tenantId(), datasetId, true);
        references.delete(actor.tenantId(), datasetId);
    }

    public Optional<PublishedQueryReference> findFor(SemanticQueryDatasetAccess access, QueryIntent intent) {
        if (access == null || intent == null || intent.exploratory() || intent.releaseScope() == null
                || intent.metrics().size() != 1 || intent.dimensions().size() > 3 || !intent.filters().isEmpty()) {
            return Optional.empty();
        }
        if (intent.dimensions().stream().anyMatch(dimension -> dimension.timeGrain() != null || dimension.calendar() != null)) {
            return Optional.empty();
        }
        if (intent.timeRange() != null && !frozenTimeRange(intent)) {
            return Optional.empty();
        }
        access.requireIntent(intent);
        String tenant = SecurityUtil.getCurrentTenantId();
        String user = SecurityUtil.getCurrentUserId();
        if (tenant == null || tenant.isBlank() || user == null || user.isBlank()) {
            return Optional.empty();
        }
        var metric = intent.metrics().getFirst();
        return references.find(tenant, access.datasetId())
                .filter(reference -> reference.accepts(intent, metric))
                .filter(reference -> matchesDimensions(reference, intent))
                .filter(reference -> matchesTime(reference, intent))
                .filter(reference -> matchesCurrentRelease(reference, tenant))
                .filter(reference -> readOnlySourceRevision(access.sourceId()).filter(reference.sourceRevision()::equals)
                        .isPresent());
    }

    private boolean matchesCurrentRelease(PublishedQueryReference reference, String tenant) {
        var version = versions.findByTenantAndId(tenant, reference.versionId()).filter(candidate ->
                candidate.getStatus() == SemanticVersionStatus.PUBLISHED
                        && reference.releaseChecksum().equals(candidate.getChecksum())
                        && reference.schemaSnapshotId().equals(candidate.getSourceSnapshotId()));
        if (version.isEmpty()) {
            return false;
        }
        return snapshots.findByTenantAndId(tenant, reference.schemaSnapshotId())
                .filter(snapshot -> snapshot.getStatus() == SchemaSnapshotStatus.READY
                        && reference.sourceId().equals(snapshot.getSourceId())
                        && reference.sourceFingerprint().equals(snapshot.getFingerprint())).isPresent();
    }

    private Release requireRelease(String tenant, Dataset dataset) {
        var version = versions.findByTenantAndId(tenant, dataset.versionId()).filter(candidate ->
                candidate.getStatus() == SemanticVersionStatus.PUBLISHED && candidate.getSourceSnapshotId() != null
                        && candidate.getChecksum() != null).orElseThrow(() -> invalid("数据集没有可用的已发布本体"));
        var artifact = artifacts.find(tenant, version.getId())
                .filter(candidate -> version.getChecksum().equals(candidate.publishedChecksum()))
                .orElseThrow(() -> invalid("独立参考需要查询就绪的结构化本体"));
        var profile = artifact.queryProfile();
        if (profile == null || !postgres(profile.databaseType()) || !dataset.sourceId().equals(profile.sourceId())
                || !dataset.physicalObject().equals(profile.physicalObject())
                || !version.getSourceSnapshotId().equals(profile.sourceSnapshotId())) {
            throw invalid("数据集与本体发布口径、对象或快照不一致");
        }
        var snapshot = snapshots.findByTenantAndId(tenant, profile.sourceSnapshotId())
                .filter(candidate -> candidate.getStatus() == SchemaSnapshotStatus.READY
                        && profile.sourceId().equals(candidate.getSourceId())
                        && profile.sourceFingerprint().equals(candidate.getFingerprint())
                        && postgres(candidate.getSchema().databaseType()))
                .orElseThrow(() -> invalid("独立参考需要与发布口径一致的完整 PostgreSQL Schema 快照"));
        return new Release(profile, snapshot, version.getChecksum());
    }

    private List<PublishedQueryReference.Dimension> dimensions(
            Register command, PublishedQueryProfile profile, Dataset dataset, SchemaObject source, SchemaObject view) {
        var requested = command.dimensions() == null ? List.<DimensionMapping>of() : command.dimensions();
        if (requested.size() > 3 || requested.stream().anyMatch(Objects::isNull)) {
            throw invalid("参考视图最多配置三个分组维度");
        }
        var profileDimensions = profile.dimensions();
        var selected = new LinkedHashMap<String, String>();
        for (var mapping : requested) {
            if (mapping.propertyId() == null || mapping.referenceColumn() == null
                    || !COLUMN.matcher(mapping.referenceColumn()).matches()
                    || selected.putIfAbsent(mapping.propertyId(), mapping.referenceColumn()) != null) {
                throw invalid("参考分组属性或参考列重复、缺失");
            }
        }
        if (profile.time() != null && selected.containsKey(profile.time().propertyId())) {
            throw invalid("已发布时间属性只能作为日期范围列，不能重复配置为分组维度");
        }
        var result = new ArrayList<PublishedQueryReference.Dimension>();
        for (var binding : profileDimensions) {
            String referenceColumn = selected.remove(binding.propertyId());
            if (referenceColumn == null) {
                continue;
            }
            if (!dataset.allowedFields().contains(binding.physicalField())) {
                throw invalid("参考分组属性不在数据集授权字段中");
            }
            var sourceField = schemaField(source, binding.physicalField());
            var target = view.fields().stream().filter(field -> referenceColumn.equals(field.name())).findFirst()
                    .orElseThrow(() -> invalid("参考视图中缺少所选分组列"));
            String sourceType = canonicalType(sourceField.dataType());
            String targetType = canonicalType(target.dataType());
            if (!sourceType.equals(targetType) || !sourceType.equals(binding.physicalType())
                    || !compatibleProfileType(binding.dataType(), logicalKind(sourceType))) {
                throw invalid("参考分组列类型必须与已发布维度一致");
            }
            result.add(new PublishedQueryReference.Dimension(binding.propertyId(), binding.iri(),
                    binding.physicalField(), binding.dataType(), binding.physicalType(), referenceColumn));
        }
        if (!selected.isEmpty()) {
            throw invalid("参考分组属性必须来自当前发布版本的维度");
        }
        return List.copyOf(result);
    }

    private PublishedQueryReference.Time time(Register command, PublishedQueryProfile profile,
                                              Dataset dataset, SchemaObject source, SchemaObject view) {
        if (command.timeReferenceColumn() == null || command.timeReferenceColumn().isBlank()) {
            return null;
        }
        if (!COLUMN.matcher(command.timeReferenceColumn()).matches()) {
            throw invalid("参考日期列名不受支持");
        }
        var binding = profile.time();
        if (binding == null || !dataset.allowedFields().contains(binding.physicalField())) {
            throw invalid("参考日期列需要一个已发布并授权的本体时间属性");
        }
        var sourceField = schemaField(source, binding.physicalField());
        var target = view.fields().stream()
                .filter(field -> command.timeReferenceColumn().equals(field.name())).findFirst()
                .orElseThrow(() -> invalid("参考视图中缺少所选日期列"));
        String sourceType = canonicalType(sourceField.dataType());
        String targetType = canonicalType(target.dataType());
        String sourceKind = logicalKind(sourceType);
        if (!"date".equals(sourceKind) && !"dateTime".equals(sourceKind)
                || !sourceType.equals(targetType) || !sourceType.equals(binding.physicalType())
                || !compatibleProfileType(binding.dataType(), sourceKind)) {
            throw invalid("参考日期列类型必须与已发布时间属性一致");
        }
        return new PublishedQueryReference.Time(binding.propertyId(), binding.iri(), binding.physicalField(),
                binding.dataType(), binding.physicalType(), profile.timeZone(), target.name());
    }

    private SchemaObject validateView(Register command, Dataset dataset, SchemaSnapshot snapshot) {
        String reference = command.referenceObject();
        String column = command.referenceColumn();
        if (!validObject(dataset.physicalObject()) || !validObject(reference) || dataset.physicalObject().equals(reference)
                || column == null || !COLUMN.matcher(column).matches()) {
            throw invalid("参考对象必须是与查询对象不同的完整 PostgreSQL Schema 视图和列");
        }
        var source = schemaObject(snapshot, dataset.physicalObject(), null, "固定快照中缺少查询表或视图");
        if (source.type() != SchemaObjectType.TABLE && source.type() != SchemaObjectType.VIEW) {
            throw invalid("固定快照中缺少查询表或视图");
        }
        var view = schemaObject(snapshot, reference, SchemaObjectType.VIEW, "固定快照中缺少普通参考视图");
        var measureColumn = view.fields().stream().filter(field -> column.equals(field.name())).findFirst().orElse(null);
        if (measureColumn == null || !NUMERIC_TYPES.contains(measureColumn.dataType().toLowerCase(Locale.ROOT))) {
            throw invalid("参考视图必须包含选定的整数或精确小数列");
        }
        return view;
    }

    private void ensureDistinctColumns(String measureColumn, List<PublishedQueryReference.Dimension> dimensions,
                                       PublishedQueryReference.Time time) {
        var used = new HashSet<String>();
        used.add(measureColumn);
        dimensions.forEach(binding -> {
            if (!used.add(binding.referenceColumn())) {
                throw invalid("参考指标列、分组列和时间列必须互不相同");
            }
        });
        if (time != null && !used.add(time.referenceColumn())) {
            throw invalid("参考指标列、分组列和时间列必须互不相同");
        }
    }

    private PublishedQueryReference reference(Dataset dataset, Release release, PublishedQueryProfile.Measure measure,
                                               Register command, List<PublishedQueryReference.Dimension> dimensions,
                                               PublishedQueryReference.Time time, String sourceRevision, String bindingKey) {
        String label = command.label() == null ? "" : command.label().strip();
        if (label.isBlank() || label.length() > 160 || label.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("参考名称需要 1 至 160 个字符且不包含控制字符");
        }
        return new PublishedQueryReference(dataset.id(), dataset.sourceId(), dataset.physicalObject(), dataset.versionId(),
                release.checksum(), release.snapshot().getId(), release.snapshot().getFingerprint(),
                measure.propertyId(), measure.iri(), measure.aggregation(), command.referenceObject(),
                command.referenceColumn(), tolerance(command.tolerance()), label, bindingKey, sourceRevision,
                measure.physicalField(), dimensions, time);
    }

    private boolean matchesDimensions(PublishedQueryReference reference, QueryIntent intent) {
        if (reference.dimensions().size() != intent.dimensions().size()) {
            return false;
        }
        for (int index = 0; index < intent.dimensions().size(); index++) {
            var configured = reference.dimensions().get(index);
            var requested = intent.dimensions().get(index);
            if (!configured.iri().equals(requested.iri()) || !configured.physicalField().equals(requested.physicalField())
                    || !reference.physicalObject().equals(requested.physicalObject())
                    || requested.timeGrain() != null || requested.calendar() != null) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesTime(PublishedQueryReference reference, QueryIntent intent) {
        var range = intent.timeRange();
        var binding = reference.time();
        if (range == null) {
            return true;
        }
        return binding != null && range.relativeDays() == null && range.from() != null
                && !range.from().isAfter(range.to())
                && java.time.temporal.ChronoUnit.DAYS.between(range.from(), range.to()) + 1 <= 366
                && binding.iri().equals(range.fieldIri())
                && binding.physicalField().equals(range.physicalField())
                && reference.physicalObject().equals(range.physicalObject())
                && range.calendar() != null && binding.timeZone().equals(range.calendar().timeZone())
                && binding.physicalType().equals(range.calendar().physicalType());
    }

    private boolean frozenTimeRange(QueryIntent intent) {
        var range = intent.timeRange();
        return range != null && range.relativeDays() == null && range.from() != null && range.to() != null
                && !range.from().isAfter(range.to())
                && java.time.temporal.ChronoUnit.DAYS.between(range.from(), range.to()) + 1 <= 366
                && range.calendar() != null;
    }

    private SchemaObject schemaObject(SchemaSnapshot snapshot, String key, SchemaObjectType type, String message) {
        return snapshot.getSchema().objects().stream()
                .filter(item -> Objects.equals(key, item.key()) && (type == null || type == item.type())).findFirst()
                .orElseThrow(() -> invalid(message));
    }

    private SchemaField schemaField(SchemaObject object, String field) {
        return object.fields().stream()
                .filter(candidate -> field.equals(candidate.name())).findFirst()
                .orElseThrow(() -> invalid("发布属性的查询列不在固定快照中"));
    }

    private boolean compatibleProfileType(String profileType, String logicalType) {
        return logicalType != null && logicalType.equals(profileType);
    }

    private String logicalKind(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "character varying", "character", "varchar", "text", "bpchar" -> "string";
            case "smallint", "integer", "bigint", "int", "int2", "int4", "int8" -> "integer";
            case "numeric", "decimal" -> "decimal";
            case "boolean", "bool" -> "boolean";
            case "date" -> "date";
            case "timestamp", "timestamp without time zone", "timestamp with time zone", "timestamptz" -> "dateTime";
            default -> null;
        };
    }

    private String canonicalType(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "timestamp" -> "timestamp without time zone";
            case "timestamptz" -> "timestamp with time zone";
            case "int2" -> "smallint";
            case "int4", "int" -> "integer";
            case "int8" -> "bigint";
            case "varchar" -> "character varying";
            case "bpchar" -> "character";
            case "bool" -> "boolean";
            case "decimal" -> "numeric";
            default -> type.toLowerCase(Locale.ROOT);
        };
    }

    private BigDecimal tolerance(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        if (!DECIMAL.matcher(value).matches()) {
            throw invalid("绝对容差须为非负十进制数，最多 20 位整数与 18 位小数");
        }
        return new BigDecimal(value);
    }

    private Optional<String> readOnlySourceRevision(String sourceId) {
        var source = sources.getById(sourceId);
        return source != null && postgres(source.getDatabaseType()) && Boolean.TRUE.equals(source.getReadOnly())
                ? Optional.of(Objects.toString(source.getUpdatedAt(), "")) : Optional.empty();
    }

    private Dataset requireDataset(String tenant, String datasetId, boolean lock) {
        return references.findDataset(tenant, datasetId, lock)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "数据集不存在"));
    }

    private Actor administrator() {
        String tenant = SecurityUtil.getCurrentTenantId();
        String user = SecurityUtil.getCurrentUserId();
        if (tenant == null || tenant.isBlank() || user == null || user.isBlank() || !authorization.hasPermission(MANAGE)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return new Actor(tenant, user);
    }

    private boolean validObject(String object) {
        return object != null && OBJECT.matcher(object).matches()
                && !object.startsWith("pg_catalog.") && !object.startsWith("information_schema.");
    }

    private boolean postgres(String dialect) {
        return "postgresql".equalsIgnoreCase(dialect) || "postgres".equalsIgnoreCase(dialect);
    }

    private String newBinding() {
        return UUID.randomUUID().toString();
    }

    private BusinessException invalid(String detail) {
        return new BusinessException(ResultCode.BAD_REQUEST, detail);
    }

    private record Actor(String tenantId, String userId) {
    }

    private record Release(PublishedQueryProfile profile, SchemaSnapshot snapshot, String checksum) {
    }
}
