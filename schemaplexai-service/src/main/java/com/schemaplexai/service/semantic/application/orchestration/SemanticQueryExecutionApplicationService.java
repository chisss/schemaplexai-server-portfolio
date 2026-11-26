package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryExecutionService;
import com.schemaplexai.service.semantic.domain.port.SemanticQuerySourcePort;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryPlanStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/** 自然语言到签名计划的服务端编排，以及 EXPLAIN/只读执行确认。 */
@Service
public class SemanticQueryExecutionApplicationService {

    private final SemanticQueryApplicationService interpretationService;
    private final SemanticQueryPlanApplicationService planService;
    private final SemanticQueryExecutionService executionService;
    private final SemanticQuerySourcePort sourcePort;
    private final ExploratoryQueryCandidateApplicationService exploration;
    private final ExploratoryQueryScopeApplicationService exploratoryScope;
    private final ExploratoryQueryPlanStore exploratoryPlans;
    private final PublishedQueryReferenceApplicationService references;

    @Autowired
    public SemanticQueryExecutionApplicationService(SemanticQueryApplicationService interpretationService,
            SemanticQueryPlanApplicationService planService, SemanticQueryExecutionService executionService,
            SemanticQuerySourcePort sourcePort, ExploratoryQueryCandidateApplicationService exploration,
            ExploratoryQueryScopeApplicationService exploratoryScope, ExploratoryQueryPlanStore exploratoryPlans,
            PublishedQueryReferenceApplicationService references) {
        this.interpretationService = interpretationService;
        this.planService = planService;
        this.executionService = executionService;
        this.sourcePort = sourcePort;
        this.exploration = exploration;
        this.exploratoryScope = exploratoryScope;
        this.exploratoryPlans = exploratoryPlans;
        this.references = references;
    }

    public SemanticQueryExecutionApplicationService(SemanticQueryApplicationService interpretationService,
            SemanticQueryPlanApplicationService planService, SemanticQueryExecutionService executionService,
            SemanticQuerySourcePort sourcePort) {
        this(interpretationService, planService, executionService, sourcePort, null, null, null, null);
    }

    public SemanticQueryExecutionApplicationService(SemanticQueryApplicationService interpretationService,
            SemanticQueryPlanApplicationService planService, SemanticQueryExecutionService executionService,
            SemanticQuerySourcePort sourcePort, ExploratoryQueryCandidateApplicationService exploration,
            ExploratoryQueryScopeApplicationService exploratoryScope, ExploratoryQueryPlanStore exploratoryPlans) {
        this(interpretationService, planService, executionService, sourcePort, exploration, exploratoryScope,
                exploratoryPlans, null);
    }

    public PreparedSemanticQuery prepare(String question, String semanticVersionId, String sourceId) {
        return prepare(question, semanticVersionId, sourceId, null);
    }

    public PreparedSemanticQuery prepare(String question, String semanticVersionId, String sourceId, String context) {
        return prepare(question, semanticVersionId, sourceId, context, null);
    }

    public PreparedSemanticQuery prepare(String question, String semanticVersionId, String sourceId, String context,
                                         SemanticQueryDatasetAccess access) {
        var interpretation = interpret(question, semanticVersionId, sourceId, context, access);
        if (interpretation.status() != QueryInterpretationStatus.NO_MATCH) {
            return compile(interpretation, sourceId, access);
        }
        if (access == null || !access.canExplore()) {
            return noExploration(interpretation, "未命中已发布查询口径，当前账号或数据集未开放探索查询");
        }
        return prepareExploration(question, context, access, interpretation, null);
    }

    public PreparedSemanticQuery preparePublished(String question, String context, SemanticQueryDatasetAccess access) {
        var interpretation = interpret(question, access.versionId(), access.sourceId(), context, access);
        return compile(interpretation, access.sourceId(), access);
    }

    public PreparedSemanticQuery prepareExploration(String question, String context, SemanticQueryDatasetAccess access,
                                                     SemanticQueryInterpretation interpretation, String datasetName) {
        if (interpretation.status() != QueryInterpretationStatus.NO_MATCH) {
            throw new IllegalArgumentException("仅未命中已发布口径的问题可进入探索");
        }
        var scope = exploratoryScope.authorize(access);
        String binding = explorationBinding(access, scope, question, context);
        try {
            var selection = datasetName == null
                    ? exploration.propose(currentTenant(), interpretation.question(), scope.fields())
                    : exploration.propose(currentTenant(), interpretation.question(), scope.fields(), datasetName);
            var intent = exploration.toIntent(interpretation.semanticVersionId(), interpretation.sourceId(),
                    access.physicalObject(), binding, scope.fields(), selection);
            access.requireIntent(intent);
            var prepared = compile(SemanticQueryInterpretation.ready(interpretation.question(), intent), intent.sourceId(), access);
            exploratoryPlans.save(receiptKey(binding, prepared.plan().planHash()), prepared.signedPlan());
            return prepared;
        } catch (BusinessException exception) {
            if (exception.getCode() == ResultCode.SEMANTIC_QUERY_NOT_INTERPRETABLE.getCode()) {
                return noExploration(interpretation, exception.getMessage());
            }
            throw exception;
        }
    }

    public PreparedSemanticQuery confirm(String question, String versionId, String sourceId, String context,
                                         SemanticQueryDatasetAccess access, String expectedHash) {
        var interpretation = interpret(question, versionId, sourceId, context, access);
        if (interpretation.status() != QueryInterpretationStatus.NO_MATCH) {
            return compile(interpretation, sourceId, access);
        }
        if (access == null || !access.canExplore()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "当前账号或数据集未开放探索查询");
        }
        var scope = exploratoryScope.authorize(access);
        if (expectedHash == null || !expectedHash.matches("[a-f0-9]{64}")) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED);
        }
        String binding = explorationBinding(access, scope, question, context);
        var signed = exploratoryPlans.find(receiptKey(binding, expectedHash))
                .orElseThrow(() -> new BusinessException(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED, "探索计划已过期或数据授权已变化"));
        var plan = signed.plan();
        if (!plan.exploratory() || !expectedHash.equals(plan.planHash())
                || !Objects.equals(access.versionId(), plan.semanticVersionId()) || !access.sourceId().equals(plan.sourceId())
                || plan.query().lineage().stream().anyMatch(line -> scope.fields().stream()
                    .noneMatch(field -> field.name().equals(line.physicalField())))) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED);
        }
        access.requireLineage(plan.query().lineage());
        return new PreparedSemanticQuery(interpretation, plan.databaseType(), plan, signed);
    }

    private SemanticQueryInterpretation interpret(String question, String versionId, String sourceId, String context,
                                                  SemanticQueryDatasetAccess access) {
        return access == null ? interpretationService.interpret(question, versionId, sourceId, context)
                : interpretationService.interpret(question, versionId, sourceId, context, access);
    }

    private PreparedSemanticQuery compile(SemanticQueryInterpretation interpretation, String sourceId,
                                          SemanticQueryDatasetAccess access) {
        if (interpretation.status() != QueryInterpretationStatus.READY) {
            return new PreparedSemanticQuery(interpretation, null, null, null);
        }
        String databaseType = sourcePort.databaseType(sourceId);
        QueryPlan plan = planService.compile(interpretation.intent(), databaseType);
        if (access != null) {
            access.requireLineage(plan.query().lineage());
            if (references != null && plan.structured() && !plan.exploratory() && plan.dialect() == QueryDialect.POSTGRESQL) {
                var reference = references.findFor(access, interpretation.intent()).orElse(null);
                if (reference != null) {
                    plan = plan.withReference(reference, sha256(plan.planHash() + ":" + reference.bindingKey()));
                }
            }
        }
        return new PreparedSemanticQuery(interpretation, databaseType, plan, executionService.sign(plan));
    }

    private PreparedSemanticQuery noExploration(SemanticQueryInterpretation interpretation, String reason) {
        var rejected = SemanticQueryInterpretation.rejected(interpretation.question(), interpretation.semanticVersionId(),
                interpretation.sourceId(), List.of(reason));
        return new PreparedSemanticQuery(rejected, null, null, null);
    }

    private String explorationBinding(SemanticQueryDatasetAccess access, ExploratoryQueryScopeApplicationService.Scope scope,
                                      String question, String context) {
        String user = currentUser();
        if ("unknown".equals(user)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "探索查询需要已认证用户");
        }
        var fields = List.of(currentTenant(), user, access.datasetId(), Long.toString(access.revision()),
                access.sourceId(), access.schemaSnapshotId(), scope.fingerprint(), scope.sourceRevision(),
                question, Objects.toString(context, ""));
        return sha256(fields.stream().map(value -> value.length() + ":" + value).collect(Collectors.joining()));
    }

    private String receiptKey(String binding, String expectedHash) {
        return sha256(binding + expectedHash);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
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
