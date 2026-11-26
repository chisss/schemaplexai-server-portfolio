package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.common.SchemaSnapshotStatus;
import com.schemaplexai.service.semantic.common.SemanticVersionStatus;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfigurationArtifact;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;
import com.schemaplexai.service.semantic.domain.port.OntologyConfigurationStorePort;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.repository.SemanticVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 用可信 Schema 快照约束探索字段和物理来源。 */
@Service
@RequiredArgsConstructor
public class ExploratoryQueryScopeApplicationService {
    private static final Pattern QUALIFIED_OBJECT = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}\\.[A-Za-z_][A-Za-z0-9_$]{0,62}");
    private final SchemaSnapshotRepository snapshots;
    private final SemanticVersionRepository versions;
    private final OntologyConfigurationStorePort artifacts;
    private final DatabaseSourceService sources;

    public record Scope(String fingerprint, String sourceRevision, List<SchemaField> fields) {}

    public Scope authorize(SemanticQueryDatasetAccess access) {
        if (!access.canExplore()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前数据集未开放探索查询");
        }
        if (!QUALIFIED_OBJECT.matcher(access.physicalObject()).matches()) {
            throw unavailable("探索查询需要带 Schema 或库名的有效对象名");
        }
        var source = sources.getById(access.sourceId());
        var dialect = source == null ? null : explorationDialect(source.getDatabaseType());
        if (dialect == null || !Boolean.TRUE.equals(source.getReadOnly())) {
            throw unavailable("探索查询需要只读 PostgreSQL 或 MySQL 数据源");
        }
        String tenant = SecurityUtil.getCurrentTenantId();
        var snapshot = snapshots.findByTenantAndId(tenant, access.schemaSnapshotId())
                .filter(candidate -> access.sourceId().equals(candidate.getSourceId())
                        && candidate.getStatus() == SchemaSnapshotStatus.READY
                        && dialect == explorationDialect(candidate.getSchema().databaseType()))
                .orElseThrow(() -> unavailable("探索查询需要当前数据源的完整 Schema 快照"));
        var object = snapshot.getSchema().objects().stream()
                .filter(candidate -> access.physicalObject().equals(candidate.key()) && candidate.namespace() != null)
                .filter(candidate -> candidate.type() == SchemaObjectType.TABLE || candidate.type() == SchemaObjectType.VIEW)
                .findFirst().orElseThrow(() -> unavailable("探索对象不在所选 Schema 快照内"));
        var fields = object.fields().stream().filter(field -> access.allowedFields().contains(field.name())).toList();
        if (fields.size() != new HashSet<>(access.allowedFields()).size()) {
            throw unavailable("数据集字段须属于所选 Schema 对象");
        }
        Set<String> governedFields = governedFields(tenant, access);
        var eligible = fields.stream().filter(field -> !governedFields.contains(field.name())).toList();
        if (eligible.isEmpty()) {
            throw unavailable("当前数据集没有可用于探索的独立字段");
        }
        return new Scope(snapshot.getFingerprint(), Objects.toString(source.getUpdatedAt(), ""), eligible);
    }

    private Set<String> governedFields(String tenant, SemanticQueryDatasetAccess access) {
        if (access.versionId() == null) {
            return Set.of();
        }
        var version = versions.findByTenantAndId(tenant, access.versionId())
                .filter(candidate -> candidate.getStatus() == SemanticVersionStatus.PUBLISHED)
                .orElseThrow(() -> unavailable("探索查询需要可用的已发布业务本体"));
        var artifact = artifacts.find(tenant, access.versionId())
                .filter(candidate -> matches(candidate, access, version.getChecksum(), version.getSourceSnapshotId()))
                .orElseThrow(() -> unavailable("探索数据集与已发布口径所用快照不一致"));
        var fields = new HashSet<String>();
        var profile = artifact.queryProfile();
        profile.measures().forEach(measure -> fields.add(measure.physicalField()));
        profile.dimensions().forEach(dimension -> fields.add(dimension.physicalField()));
        profile.filters().forEach(filter -> fields.add(filter.physicalField()));
        if (profile.time() != null) {
            fields.add(profile.time().physicalField());
        }
        if (artifact.configuration() != null) {
            var classes = artifact.configuration().classes().stream()
                    .filter(concept -> access.physicalObject().equals(concept.sourceObject()))
                    .map(concept -> concept.id()).toList();
            artifact.configuration().properties().stream()
                    .filter(property -> classes.contains(property.classId()) && property.sourceField() != null)
                    .forEach(property -> fields.add(property.sourceField()));
        }
        return fields;
    }

    private boolean matches(OntologyConfigurationArtifact artifact, SemanticQueryDatasetAccess access,
                            String checksum, String snapshotId) {
        var profile = artifact.queryProfile();
        return artifact.published() && profile != null && Objects.equals(checksum, artifact.publishedChecksum())
                && Objects.equals(snapshotId, access.schemaSnapshotId())
                && Objects.equals(profile.sourceSnapshotId(), access.schemaSnapshotId())
                && profile.sourceId().equals(access.sourceId()) && profile.physicalObject().equals(access.physicalObject());
    }

    private QueryDialect explorationDialect(String databaseType) {
        try {
            var dialect = QueryDialect.fromDatabaseType(databaseType);
            return dialect.supportsPublishedQuery() ? dialect : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private BusinessException unavailable(String message) {
        return new BusinessException(ResultCode.SEMANTIC_QUERY_SOURCE_UNAVAILABLE, message);
    }
}
