package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.semantic.SchemaSnapshotMapper;
import com.schemaplexai.model.entity.semantic.SchemaSnapshotEntity;
import com.schemaplexai.service.semantic.common.SchemaSnapshotStatus;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** MyBatis Schema 快照仓储适配器。 */
@Repository
@RequiredArgsConstructor
public class MybatisSchemaSnapshotRepository implements SchemaSnapshotRepository {

    private final SchemaSnapshotMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public void insert(SchemaSnapshot snapshot) {
        mapper.insert(toEntity(snapshot));
    }

    @Override
    public Optional<SchemaSnapshot> findByFingerprint(
            String tenantId,
            String sourceId,
            String fingerprint) {
        return Optional.ofNullable(mapper.selectByFingerprint(tenantId, sourceId, fingerprint))
                .map(this::toDomain);
    }

    @Override
    public List<SchemaSnapshot> findAllBySource(String tenantId, String sourceId) {
        return mapper.selectAllBySource(tenantId, sourceId).stream().map(this::toDomain).toList();
    }

    private SchemaSnapshotEntity toEntity(SchemaSnapshot snapshot) {
        SchemaSnapshotEntity entity = new SchemaSnapshotEntity();
        entity.setId(snapshot.getId());
        entity.setTenantId(snapshot.getTenantId());
        entity.setSourceId(snapshot.getSourceId());
        entity.setFingerprint(snapshot.getFingerprint());
        entity.setSchemaJson(objectMapper.convertValue(snapshot.getSchema(), new TypeReference<Map<String, Object>>() { }));
        entity.setStatus(snapshot.getStatus().name().toLowerCase(Locale.ROOT));
        entity.setScannedAt(snapshot.getScannedAt());
        entity.setDeleted(0);
        return entity;
    }

    private SchemaSnapshot toDomain(SchemaSnapshotEntity entity) {
        DatabaseSchema schema = objectMapper.convertValue(entity.getSchemaJson(), DatabaseSchema.class);
        return SchemaSnapshot.restore(
                entity.getId(),
                entity.getTenantId(),
                entity.getSourceId(),
                entity.getFingerprint(),
                schema,
                SchemaSnapshotStatus.valueOf(entity.getStatus().toUpperCase(Locale.ROOT)),
                entity.getScannedAt());
    }
}
