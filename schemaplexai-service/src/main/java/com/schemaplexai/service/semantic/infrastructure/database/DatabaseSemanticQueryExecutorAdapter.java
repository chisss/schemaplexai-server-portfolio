package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest;
import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;

import java.util.List;
import java.util.Map;

/** 关系型数据库计划适配器，执行前再次经过现有 SqlReadOnlyGuard。 */
public final class DatabaseSemanticQueryExecutorAdapter implements SemanticQueryExecutorPort {

    private final DatabaseSourceService databaseSourceService;
    private final QueryParameterBinder parameterBinder = new QueryParameterBinder();

    public DatabaseSemanticQueryExecutorAdapter(DatabaseSourceService databaseSourceService) {
        this.databaseSourceService = databaseSourceService;
    }

    @Override
    public QueryExplainResult explain(QueryPlan plan, QueryExecutionLimits limits) {
        requireSql(plan);
        DatabaseQueryResultVO result = execute(plan, "EXPLAIN " + parameterBinder.bind(
                plan.query().statement(), plan.query().parameters()), limits);
        return new QueryExplainResult(
                plan.planHash(),
                plan.query().language(),
                plan.query().statement(),
                plan.query().parameters().stream().map(parameter -> parameter.name()).toList(),
                plan.query().columns(),
                plan.query().lineage(),
                result.getWarnings());
    }

    @Override
    public QueryExecutionResult execute(QueryPlan plan, QueryExecutionLimits limits) {
        requireSql(plan);
        DatabaseQueryResultVO result = execute(
                plan, parameterBinder.bind(plan.query().statement(), plan.query().parameters()), limits);
        return new QueryExecutionResult(
                plan.planHash(),
                result.getRows(),
                plan.query().columns(),
                plan.query().lineage(),
                result.getRowCount() == null ? 0 : result.getRowCount(),
                Boolean.TRUE.equals(result.getTruncated()),
                result.getElapsedMs() == null ? 0 : result.getElapsedMs(),
                null,
                result.getWarnings());
    }

    private DatabaseQueryResultVO execute(QueryPlan plan, String sql, QueryExecutionLimits limits) {
        DatabaseQueryExecuteRequest request = new DatabaseQueryExecuteRequest();
        request.setSql(sql);
        request.setLimit(limits.maxRows());
        return databaseSourceService.executeQuery(plan.sourceId(), request);
    }

    private void requireSql(QueryPlan plan) {
        if (plan == null || !"SQL".equals(plan.query().language())) {
            throw new IllegalArgumentException("database adapter only supports compiled SQL plans");
        }
    }
}
