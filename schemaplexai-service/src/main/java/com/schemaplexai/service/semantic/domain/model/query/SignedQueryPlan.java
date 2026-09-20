package com.schemaplexai.service.semantic.domain.model.query;

import java.util.Objects;

/** 仅由服务端签发、可进入 EXPLAIN/执行边界的计划。 */
public record SignedQueryPlan(QueryPlan plan, String signature) {

    public SignedQueryPlan {
        plan = Objects.requireNonNull(plan, "plan is required");
        if (signature == null || signature.isBlank()) {
            throw new IllegalArgumentException("signature is required");
        }
        signature = signature.trim();
    }
}
