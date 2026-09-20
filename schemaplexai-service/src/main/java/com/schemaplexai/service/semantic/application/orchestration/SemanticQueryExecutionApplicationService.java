package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryExecutionService;
import com.schemaplexai.service.semantic.domain.port.SemanticQuerySourcePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 自然语言到签名计划的服务端编排，以及 EXPLAIN/只读执行确认。 */
@Service
@RequiredArgsConstructor
public class SemanticQueryExecutionApplicationService {

    private final SemanticQueryApplicationService interpretationService;
    private final SemanticQueryPlanApplicationService planService;
    private final SemanticQueryExecutionService executionService;
    private final SemanticQuerySourcePort sourcePort;

    public PreparedSemanticQuery prepare(String question, String semanticVersionId, String sourceId) {
        return prepare(question, semanticVersionId, sourceId, null);
    }

    public PreparedSemanticQuery prepare(String question, String semanticVersionId, String sourceId, String context) {
        SemanticQueryInterpretation interpretation = interpretationService.interpret(
                question, semanticVersionId, sourceId, context);
        if (interpretation.status() != QueryInterpretationStatus.READY) {
            return new PreparedSemanticQuery(interpretation, null, null, null);
        }
        String databaseType = sourcePort.databaseType(sourceId);
        QueryPlan plan = planService.compile(interpretation.intent(), databaseType);
        return new PreparedSemanticQuery(interpretation, databaseType, plan, executionService.sign(plan));
    }

    public QueryExplainResult explain(PreparedSemanticQuery prepared, String expectedPlanHash,
                                      Integer maxRows, Integer timeoutSeconds) {
        verifyPlan(prepared, expectedPlanHash);
        return executionService.explain(
                prepared.signedPlan(), currentTenant(), currentUser(), limits(maxRows, timeoutSeconds));
    }

    public QueryExecutionResult execute(PreparedSemanticQuery prepared, String expectedPlanHash,
                                        Integer maxRows, Integer timeoutSeconds) {
        verifyPlan(prepared, expectedPlanHash);
        return executionService.execute(
                prepared.signedPlan(), currentTenant(), currentUser(), limits(maxRows, timeoutSeconds));
    }

    private void verifyPlan(PreparedSemanticQuery prepared, String expectedPlanHash) {
        if (prepared == null || !prepared.ready()) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_NOT_INTERPRETABLE, "问题尚未形成可执行计划");
        }
        if (expectedPlanHash == null || expectedPlanHash.isBlank()
                || !expectedPlanHash.equals(prepared.plan().planHash())) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED);
        }
    }

    private QueryExecutionLimits limits(Integer maxRows, Integer timeoutSeconds) {
        return new QueryExecutionLimits(
                maxRows == null ? 5000 : maxRows,
                timeoutSeconds == null ? 30 : timeoutSeconds);
    }

    private String currentTenant() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId;
    }

    private String currentUser() {
        String userId = SecurityUtil.getCurrentUserId();
        return userId == null || userId.isBlank() ? "unknown" : userId;
    }
}
