package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;

/** 按已签名计划语言路由关系型和 MongoDB 执行适配器。 */
public final class RoutingSemanticQueryExecutorAdapter implements SemanticQueryExecutorPort {

    private final SemanticQueryExecutorPort sql;
    private final SemanticQueryExecutorPort mongo;

    public RoutingSemanticQueryExecutorAdapter(
            SemanticQueryExecutorPort sql,
            SemanticQueryExecutorPort mongo) {
        this.sql = sql;
        this.mongo = mongo;
    }

    @Override
    public QueryExplainResult explain(QueryPlan plan, QueryExecutionLimits limits) {
        return delegate(plan).explain(plan, limits);
    }

    @Override
    public QueryExecutionResult execute(QueryPlan plan, QueryExecutionLimits limits) {
        return delegate(plan).execute(plan, limits);
    }

    private SemanticQueryExecutorPort delegate(QueryPlan plan) {
        if (plan == null || plan.query() == null) {
            throw new IllegalArgumentException("query plan is required");
        }
        return "MONGO_AGGREGATION".equals(plan.query().language()) ? mongo : sql;
    }
}
