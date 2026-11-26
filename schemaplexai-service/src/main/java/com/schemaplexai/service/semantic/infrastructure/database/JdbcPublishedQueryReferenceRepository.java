package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import com.schemaplexai.service.semantic.domain.repository.PublishedQueryReferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcPublishedQueryReferenceRepository implements PublishedQueryReferenceRepository {

    private static final String DATASET = """
            SELECT id, source_id, version_id, physical_object, allowed_fields, enabled
            FROM sf_desktop_dataset
            WHERE tenant_id=? AND id=?
            """;

    private static final RowMapper<Dataset> DATASET_MAPPER = (row, ignored) ->
            new Dataset(row.getString("id"), row.getString("source_id"), row.getString("version_id"),
                    row.getString("physical_object"), List.of((String[]) row.getArray("allowed_fields").getArray()),
                    row.getBoolean("enabled"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private RowMapper<PublishedQueryReference> referenceMapper() {
        return (row, ignored) -> {
            var bindings = decodeBindings(row.getString("dimensions_json"));
            if (bindings.time() == null && row.getString("time_reference_column") != null) {
                throw new java.sql.SQLException("Time reference binding is missing its pinned ontology metadata");
            }
            if (bindings.time() != null && !bindings.time().referenceColumn()
                    .equals(row.getString("time_reference_column"))) {
                throw new java.sql.SQLException("Time reference columns do not agree");
            }
            return new PublishedQueryReference(row.getString("dataset_id"), row.getString("source_id"),
                    row.getString("physical_object"), row.getString("version_id"), row.getString("release_checksum"),
                    row.getString("schema_snapshot_id"), row.getString("source_fingerprint"),
                    row.getString("measure_property_id"), row.getString("measure_iri"), row.getString("aggregation"),
                    row.getString("reference_view"), row.getString("reference_column"), row.getBigDecimal("tolerance"),
                    row.getString("label"), row.getString("binding_key"), row.getString("source_revision"),
                    row.getString("measure_column"), bindings.dimensions(), bindings.time());
        };
    }

    @Override
    public Optional<Dataset> findDataset(String tenantId, String datasetId, boolean lock) {
        return jdbc.query(lock ? DATASET + " FOR UPDATE" : DATASET, DATASET_MAPPER, tenantId, datasetId)
                .stream().findFirst();
    }

    @Override
    public Optional<PublishedQueryReference> find(String tenantId, String datasetId) {
        return jdbc.query("""
                SELECT dataset_id, source_id, physical_object, version_id, release_checksum, schema_snapshot_id,
                       source_fingerprint, measure_property_id, measure_iri, measure_column, aggregation,
                       reference_view, reference_column, tolerance, label, binding_key, source_revision,
                       dimensions_json::text AS dimensions_json, time_reference_column
                FROM sf_semantic_query_reference
                WHERE tenant_id=? AND dataset_id=?
                """, referenceMapper(), tenantId, datasetId).stream().findFirst();
    }

    @Override
    public void save(String tenantId, String userId, PublishedQueryReference reference) {
        jdbc.update("""
                INSERT INTO sf_semantic_query_reference
                    (tenant_id, dataset_id, source_id, physical_object, version_id, release_checksum,
                     schema_snapshot_id, source_fingerprint, measure_property_id, measure_iri, measure_column,
                     aggregation, reference_view, reference_column, tolerance, label, binding_key,
                     source_revision, dimensions_json, time_reference_column, updated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS JSONB),?,?)
                ON CONFLICT (tenant_id, dataset_id) DO UPDATE SET
                    source_id=EXCLUDED.source_id, physical_object=EXCLUDED.physical_object,
                    version_id=EXCLUDED.version_id, release_checksum=EXCLUDED.release_checksum,
                    schema_snapshot_id=EXCLUDED.schema_snapshot_id, source_fingerprint=EXCLUDED.source_fingerprint,
                    measure_property_id=EXCLUDED.measure_property_id, measure_iri=EXCLUDED.measure_iri,
                    measure_column=EXCLUDED.measure_column, aggregation=EXCLUDED.aggregation,
                    reference_view=EXCLUDED.reference_view, reference_column=EXCLUDED.reference_column,
                    tolerance=EXCLUDED.tolerance, label=EXCLUDED.label, binding_key=EXCLUDED.binding_key,
                    source_revision=EXCLUDED.source_revision, dimensions_json=EXCLUDED.dimensions_json,
                    time_reference_column=EXCLUDED.time_reference_column,
                    updated_by=EXCLUDED.updated_by, updated_at=now()
                """, tenantId, reference.datasetId(), reference.sourceId(), reference.physicalObject(),
                reference.versionId(), reference.releaseChecksum(), reference.schemaSnapshotId(),
                reference.sourceFingerprint(), reference.measurePropertyId(), reference.measureIri(),
                reference.measureColumn(), reference.aggregation(), reference.referenceView(), reference.referenceColumn(),
                reference.tolerance(), reference.label(), reference.bindingKey(), reference.sourceRevision(),
                encodeBindings(reference), reference.time() == null ? null : reference.time().referenceColumn(), userId);
    }

    private Bindings decodeBindings(String json) throws java.sql.SQLException {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return new Bindings(List.of(), null);
        }
        try {
            if (json.trim().startsWith("[]")) {
                return new Bindings(List.of(), null);
            }
            var bindings = objectMapper.readValue(json, Bindings.class);
            return bindings == null ? new Bindings(List.of(), null)
                    : new Bindings(bindings.dimensions() == null ? List.of() : List.copyOf(bindings.dimensions()),
                    bindings.time());
        } catch (JsonProcessingException exception) {
            throw new java.sql.SQLException("Unable to read registered reference dimensions", exception);
        }
    }

    private String encodeBindings(PublishedQueryReference reference) {
        try {
            return objectMapper.writeValueAsString(new Bindings(reference.dimensions(), reference.time()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize reference dimensions", exception);
        }
    }

    private record Bindings(List<PublishedQueryReference.Dimension> dimensions, PublishedQueryReference.Time time) {
    }

    @Override
    public void delete(String tenantId, String datasetId) {
        jdbc.update("DELETE FROM sf_semantic_query_reference WHERE tenant_id=? AND dataset_id=?", tenantId, datasetId);
    }
}
