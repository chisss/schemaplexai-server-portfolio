package com.schemaplexai.service.semantic.application.query;

import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;

/** 服务端从自然语言生成的临时计划上下文，不接受客户端物理映射。 */
public record PreparedSemanticQuery(
        SemanticQueryInterpretation interpretation,
        String databaseType,
        QueryPlan plan,
        SignedQueryPlan signedPlan) {

    public boolean ready() {
        return plan != null && signedPlan != null;
    }
}
