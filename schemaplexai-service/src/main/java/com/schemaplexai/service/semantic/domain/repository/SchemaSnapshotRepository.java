package com.schemaplexai.service.semantic.domain.repository;

import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;

import java.util.List;
import java.util.Optional;

/** Schema 快照仓储端口。 */
public interface SchemaSnapshotRepository {

    void insert(SchemaSnapshot snapshot);

    Optional<SchemaSnapshot> findByFingerprint(String tenantId, String sourceId, String fingerprint);

    Optional<SchemaSnapshot> findByTenantAndId(String tenantId, String snapshotId);

    List<SchemaSnapshot> findAllBySource(String tenantId, String sourceId);
}
