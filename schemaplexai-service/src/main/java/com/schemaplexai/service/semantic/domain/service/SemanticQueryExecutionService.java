package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.QueryAuditEvent;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;

import java.time.Instant;
import java.util.UUID;

/** 计划签发、EXPLAIN 和只读执行编排；调用外部数据库只通过受控 Port。 */
public final class SemanticQueryExecutionService {

    private final QueryPlanSignatureService signatureService;
    private final QueryPlanPolicy policy;
    private final SemanticQueryExecutorPort executor;
    private final SemanticQueryAuditPort audit;

    public SemanticQueryExecutionService(
            QueryPlanSignatureService signatureService,
            QueryPlanPolicy policy,
            SemanticQueryExecutorPort executor,
            SemanticQueryAuditPort audit) {
        this.signatureService = signatureService;
        this.policy = policy;
        this.executor = executor;
        this.audit = audit;
    }

    public SignedQueryPlan sign(QueryPlan plan) {
        return signatureService.sign(plan);
    }

    public QueryExplainResult explain(
            SignedQueryPlan signedPlan,
            String tenantId,
            String userId,
            QueryExecutionLimits limits) {
        validate(signedPlan, limits);
        try {
            QueryExplainResult result = executor.explain(signedPlan.plan(), limits);
            record(tenantId, userId, signedPlan, "EXPLAIN", "SUCCESS", 0, 0);
            return result;
        } catch (RuntimeException exception) {
            record(tenantId, userId, signedPlan, "EXPLAIN", "FAILED", 0, 0);
            throw exception;
        }
    }

    public QueryExecutionResult execute(
            SignedQueryPlan signedPlan,
            String tenantId,
            String userId,
            QueryExecutionLimits limits) {
        validate(signedPlan, limits);
        long startedAt = System.currentTimeMillis();
        try {
            QueryExecutionResult result = executor.execute(signedPlan.plan(), limits);
            long elapsed = Math.max(result.elapsedMs(), System.currentTimeMillis() - startedAt);
            record(tenantId, userId, signedPlan, "EXECUTE", "SUCCESS", result.rowCount(), elapsed);
            return result;
        } catch (RuntimeException exception) {
            record(tenantId, userId, signedPlan, "EXECUTE", "FAILED", 0,
                    Math.max(0, System.currentTimeMillis() - startedAt));
            throw exception;
        }
    }

    private void validate(SignedQueryPlan signedPlan, QueryExecutionLimits limits) {
        signatureService.verify(signedPlan);
        policy.validate(signedPlan.plan(), limits);
    }

    private void record(
            String tenantId,
            String userId,
            SignedQueryPlan signedPlan,
            String action,
            String outcome,
            int rowCount,
            long elapsedMs) {
        audit.record(new QueryAuditEvent(
                UUID.randomUUID().toString(),
                tenantId,
                userId,
                signedPlan.plan().planHash(),
                signedPlan.plan().sourceId(),
                action,
                outcome,
                rowCount,
                elapsedMs,
                Instant.now()));
    }
}
