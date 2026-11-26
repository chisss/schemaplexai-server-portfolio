package com.schemaplexai.service.semantic.domain.port;

import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;

import java.util.Optional;

/** 只保存审批所需的已签名探索计划。 */
public interface ExploratoryQueryPlanStore {
    void save(String key, SignedQueryPlan plan);

    Optional<SignedQueryPlan> find(String key);
}
