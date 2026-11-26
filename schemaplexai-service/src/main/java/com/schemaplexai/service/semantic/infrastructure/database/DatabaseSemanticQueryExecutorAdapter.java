package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest;
import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;

/** 关系型数据库计划适配器，执行前再次经过现有 SqlReadOnlyGuard。 */
public final class DatabaseSemanticQueryExecutorAdapter implements SemanticQueryExecutorPort {

    private final DatabaseSourceService databaseSourceService;
    private final QueryParameterBinder parameterBinder = new QueryParameterBinder();
    private final PostgresReferenceProjection referenceProjection = new PostgresReferenceProjection();
    private final StructuredQueryResultValidator resultValidator = new StructuredQueryResultValidator();

    public DatabaseSemanticQueryExecutorAdapter(DatabaseSourceService databaseSourceService) {
        this.databaseSourceService = databaseSourceService;
    }

    @Override
    public QueryExplainResult explain(QueryPlan plan, QueryExecutionLimits limits) {
        requireSql(plan);
        DatabaseQueryResultVO result = execute(plan, "EXPLAIN " + statement(plan), limits);
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
        DatabaseQueryResultVO result = execute(plan, statement(plan), limits);
        var checked = plan.structured()
                ? resultValidator.validate(plan, result, Math.min(limits.maxRows(), PostgresReferenceProjection.MAX_GROUPS)) : null;
        int rowCount = checked == null ? (result.getRowCount() == null ? 0 : result.getRowCount()) : checked.rowCount();
        boolean truncated = checked == null ? Boolean.TRUE.equals(result.getTruncated()) : checked.truncated();
        return new QueryExecutionResult(
                plan.planHash(),
                checked == null ? result.getRows() : checked.rows(),
                plan.query().columns(),
                plan.query().lineage(),
                rowCount,
                truncated,
                result.getElapsedMs() == null ? 0 : result.getElapsedMs(),
                null,
                result.getWarnings(),
                checked == null ? null : checked.validation());
    }

    private String statement(QueryPlan plan) {
        String candidate = parameterBinder.bind(plan.query().statement(), plan.query().parameters(),
                QueryDialect.fromDatabaseType(plan.databaseType()));
        return plan.reference() == null ? candidate : referenceProjection.addReference(plan, candidate);
    }

    private DatabaseQueryResultVO execute(QueryPlan plan, String sql, QueryExecutionLimits limits) {
        DatabaseQueryExecuteRequest request = new DatabaseQueryExecuteRequest();
        request.setSql(sql);
        if (plan.structured()) {
            request.setLimit(plan.reference() == null ? Math.min(limits.maxRows(), PostgresReferenceProjection.MAX_GROUPS)
                    : PostgresReferenceProjection.OUTPUT_LIMIT);
            if (plan.reference() != null) {
                return databaseSourceService.executeSemanticQuery(plan.sourceId(), request, limits.timeoutSeconds(),
                        plan.reference().sourceRevision());
            }
            return databaseSourceService.executeSemanticQuery(plan.sourceId(), request, limits.timeoutSeconds());
        }
        request.setLimit(limits.maxRows());
        return databaseSourceService.executeQuery(plan.sourceId(), request);
    }

    private void requireSql(QueryPlan plan) {
        if (plan == null || !"SQL".equals(plan.query().language())) {
            throw new IllegalArgumentException("database adapter only supports compiled SQL plans");
        }
    }
}
