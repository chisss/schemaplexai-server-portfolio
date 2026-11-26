package com.schemaplexai.service.semantic.domain.model.query;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** 由管理员登记并与发布口径绑定的单值参考视图。 */
public record PublishedQueryReference(
        String datasetId, String sourceId, String physicalObject, String versionId, String releaseChecksum,
        String schemaSnapshotId, String sourceFingerprint, String measurePropertyId, String measureIri,
        String aggregation, String referenceView, String referenceColumn, BigDecimal tolerance,
        String label, String bindingKey, String sourceRevision, String measureColumn,
        List<Dimension> dimensions, Time time) {

    public record Dimension(String propertyId, String iri, String physicalField, String dataType,
                            String physicalType, String referenceColumn) {}

    public record Time(String propertyId, String iri, String physicalField, String dataType,
                       String physicalType, String timeZone, String referenceColumn) {}

    public PublishedQueryReference(String datasetId, String sourceId, String physicalObject, String versionId,
                                   String releaseChecksum, String schemaSnapshotId, String sourceFingerprint,
                                   String measurePropertyId, String measureIri, String aggregation, String referenceView,
                                   String referenceColumn, BigDecimal tolerance, String label, String bindingKey) {
        this(datasetId, sourceId, physicalObject, versionId, releaseChecksum, schemaSnapshotId, sourceFingerprint,
                measurePropertyId, measureIri, aggregation, referenceView, referenceColumn, tolerance, label, bindingKey,
                null, null, List.of(), null);
    }

    public PublishedQueryReference(String datasetId, String sourceId, String physicalObject, String versionId,
                                   String releaseChecksum, String schemaSnapshotId, String sourceFingerprint,
                                   String measurePropertyId, String measureIri, String aggregation, String referenceView,
                                   String referenceColumn, BigDecimal tolerance, String label, String bindingKey,
                                   String sourceRevision, String measureColumn) {
        this(datasetId, sourceId, physicalObject, versionId, releaseChecksum, schemaSnapshotId, sourceFingerprint,
                measurePropertyId, measureIri, aggregation, referenceView, referenceColumn, tolerance, label, bindingKey,
                sourceRevision, measureColumn, List.of(), null);
    }

    public PublishedQueryReference {
        tolerance = tolerance == null ? BigDecimal.ZERO : tolerance.stripTrailingZeros();
        if (tolerance.scale() < 0) {
            tolerance = tolerance.setScale(0);
        }
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
    }

    public boolean accepts(QueryIntent intent, QueryMetric metric) {
        return Objects.equals(versionId, intent.semanticVersionId()) && sourceId.equals(intent.sourceId())
                && physicalObject.equals(metric.physicalObject()) && measureIri.equals(metric.iri())
                && aggregation.equals(metric.aggregation()) && Objects.equals(measureColumn, metric.physicalField())
                && dimensions.stream().map(Dimension::iri).toList().equals(
                        intent.dimensions().stream().map(QueryDimension::iri).toList())
                && (intent.timeRange() == null || (time != null
                    && time.iri().equals(intent.timeRange().fieldIri())));
    }
}
