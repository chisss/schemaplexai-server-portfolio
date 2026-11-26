package com.schemaplexai.service.semantic.domain.repository;

import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;

import java.util.List;
import java.util.Optional;

/** 查询参考配置和应用该配置的数据集绑定。 */
public interface PublishedQueryReferenceRepository {

    Optional<Dataset> findDataset(String tenantId, String datasetId, boolean lock);

    Optional<PublishedQueryReference> find(String tenantId, String datasetId);

    void save(String tenantId, String userId, PublishedQueryReference reference);

    void delete(String tenantId, String datasetId);

    record Dataset(String id, String sourceId, String versionId, String physicalObject,
                   List<String> allowedFields, boolean enabled) {
        public Dataset {
            allowedFields = List.copyOf(allowedFields);
        }
    }
}
