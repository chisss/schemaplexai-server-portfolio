package com.schemaplexai.service.semantic.application.query;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryLineage;

import java.util.List;
import java.util.Objects;

/** 当前请求已从数据集授权表加载的行范围与字段白名单。 */
public record SemanticQueryDatasetAccess(
        String datasetId, long revision, String versionId, String sourceId, String physicalObject,
        List<String> allowedFields, String schemaSnapshotId, boolean canExplore) {

    public SemanticQueryDatasetAccess(String datasetId, long revision, String versionId, String sourceId,
                                      String physicalObject, List<String> allowedFields) {
        this(datasetId, revision, versionId, sourceId, physicalObject, allowedFields, null, false);
    }

    public SemanticQueryDatasetAccess {
        allowedFields = List.copyOf(allowedFields);
    }

    public void requireBinding(String versionId, String sourceId) {
        if (!Objects.equals(this.versionId, versionId) || !this.sourceId.equals(sourceId)) {
            throw denied();
        }
    }

    public void requireIntent(QueryIntent intent) {
        requireBinding(intent.semanticVersionId(), intent.sourceId());
        intent.metrics().forEach(metric -> requireField(metric.physicalObject(), metric.physicalField()));
        intent.dimensions().forEach(dimension -> requireField(dimension.physicalObject(), dimension.physicalField()));
        intent.filters().forEach(filter -> requireField(filter.physicalObject(), filter.physicalField()));
        if (intent.timeRange() != null) {
            requireField(intent.timeRange().physicalObject(), intent.timeRange().physicalField());
        }
    }

    public void requireLineage(List<QueryLineage> lineage) {
        if (lineage.isEmpty()) {
            throw denied();
        }
        for (var line : lineage) {
            if (!sourceId.equals(line.sourceId())) {
                throw denied();
            }
            requireField(line.physicalObject(), line.physicalField());
        }
    }

    private void requireField(String object, String field) {
        if (!physicalObject.equals(object) || !allowedFields.contains(field)) {
            throw denied();
        }
    }

    private BusinessException denied() {
        return new BusinessException(ResultCode.FORBIDDEN, "查询超出数据集的授权范围");
    }
}
