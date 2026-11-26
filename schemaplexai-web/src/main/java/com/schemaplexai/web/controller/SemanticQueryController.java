package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.dto.semantic.SemanticQueryExecutionRequest;
import com.schemaplexai.model.dto.semantic.SemanticQueryInterpretRequest;
import com.schemaplexai.model.vo.semantic.SemanticQueryExecutionVO;
import com.schemaplexai.model.vo.semantic.SemanticQueryExplainVO;
import com.schemaplexai.model.vo.semantic.SemanticQueryInterpretVO;
import com.schemaplexai.model.vo.semantic.SemanticQueryPlanVO;
import com.schemaplexai.model.vo.semantic.SemanticQueryValidationVO;
import com.schemaplexai.service.desktop.DesktopDataService;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.CompositeQueryPlanner;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryExecutionApplicationService;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryLineage;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryClarification;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 自然语言语义解释、计划预览和受控只读执行 REST 入口。 */
@Validated
@RestController
@RequestMapping("/semantic/query")
public class SemanticQueryController {

    private final SemanticQueryApplicationService queryService;
    private final SemanticQueryExecutionApplicationService executionService;
    private final DesktopDataService datasets;
    private final CompositeQueryPlanner planner;
    private final QueryProgressStream streams;

    @Autowired
    public SemanticQueryController(SemanticQueryApplicationService queryService,
            SemanticQueryExecutionApplicationService executionService, DesktopDataService datasets,
            CompositeQueryPlanner planner, QueryProgressStream streams) {
        this.queryService = queryService;
        this.executionService = executionService;
        this.datasets = datasets;
        this.planner = planner;
        this.streams = streams;
    }

    public SemanticQueryController(SemanticQueryApplicationService queryService,
            SemanticQueryExecutionApplicationService executionService, DesktopDataService datasets) {
        this(queryService, executionService, datasets, null, null);
    }

    @GetMapping("/datasets")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:interpret')")
    public R<List<DesktopDataService.Dataset>> datasets() {
        return R.ok(datasets.catalog());
    }

    @PostMapping("/interpret")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:interpret')")
    public R<SemanticQueryInterpretVO> interpret(@Valid @RequestBody SemanticQueryInterpretRequest request) {
        var binding = binding(request.datasetId(), request.semanticVersionId(), request.sourceId());
        SemanticQueryInterpretation interpretation = queryService.interpret(
                request.question(), binding.versionId(), binding.sourceId(), request.context(), binding.access());
        return R.ok(toView(interpretation));
    }

    @PostMapping("/plan")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:interpret')")
    public R<SemanticQueryPlanVO> plan(@Valid @RequestBody SemanticQueryExecutionRequest request) {
        if (blank(request.datasetId()) && blank(request.semanticVersionId()) && blank(request.sourceId())) {
            var plan = datasets.planAutomatically(request.question(), request.context());
            return R.ok(toPlanView(plan.prepared(), plan.dataset() == null ? null : plan.dataset().id(),
                    plan.dataset() == null ? null : plan.dataset().name()));
        }
        var binding = binding(request.datasetId(), request.semanticVersionId(), request.sourceId());
        PreparedSemanticQuery prepared = executionService.prepare(
                request.question(), binding.versionId(), binding.sourceId(), request.context(), binding.access());
        return R.ok(toPlanView(prepared, request.datasetId(), null));
    }

    @PostMapping(value = "/plan-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:interpret')")
    public SseEmitter planStream(@Valid @RequestBody SemanticQueryExecutionRequest request) {
        return streams.start(send -> {
            send.accept(Map.of("type", "progress", "message", "正在识别问题中的查询目标"));
            var composite = planner.plan(request.question(), request.context());
            if (composite.parts().size() > 1 && composite.presentation() != CompositeQueryPlanner.Presentation.DISTINCT) {
                var joint = datasets.planJointly(request.question(), request.context(), composite.parts());
                if (joint != null) {
                    var view = toPlanView(joint.prepared(), joint.dataset().id(), joint.dataset().name());
                    send.accept(Map.of("type", "progress", "index", 0, "question", request.question(),
                            "message", "已验证多个指标可以共享已发布口径和统计条件"));
                    send.accept(Map.of("type", "plan", "index", 0, "question", request.question(), "plan", view));
                    send.accept(Map.of("type", "done", "presentation", "JOINT"));
                    return;
                }
            }
            for (int index = 0; index < composite.parts().size(); index++) {
                var part = composite.parts().get(index);
                int position = index;
                send.accept(Map.of("type", "progress", "index", index, "question", part.question(),
                        "message", "正在检查授权范围和已发布口径"));
                try {
                    var automatic = datasets.planAutomatically(part.question(), request.context(),
                            message -> send.accept(Map.of("type", "progress", "index", position,
                                    "question", part.question(), "message", message)));
                    var dataset = automatic.dataset();
                    var view = toPlanView(automatic.prepared(), dataset == null ? null : dataset.id(),
                            dataset == null ? null : dataset.name());
                    send.accept(Map.of("type", "plan", "index", index, "question", part.question(), "plan", view));
                } catch (BusinessException failure) {
                    send.accept(Map.of("type", "error", "index", index, "question", part.question(),
                            "message", "该项查询暂时无法规划，请补充业务条件后重试。"));
                }
            }
            send.accept(Map.of("type", "done", "presentation", composite.presentation().name()));
        });
    }

    @PostMapping("/explain")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:execute')")
    @Transactional
    public R<SemanticQueryExplainVO> explain(@Valid @RequestBody SemanticQueryExecutionRequest request) {
        PreparedSemanticQuery prepared = confirm(request);
        QueryExplainResult result = executionService.explain(
                prepared, request.expectedPlanHash(), request.maxRows(), request.timeoutSeconds());
        return R.ok(toExplainView(result, prepared.plan().exploratory()));
    }

    @PostMapping("/execute")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:execute')")
    @Transactional
    public R<SemanticQueryExecutionVO> execute(@Valid @RequestBody SemanticQueryExecutionRequest request) {
        PreparedSemanticQuery prepared = confirm(request);
        QueryExecutionResult result = executionService.execute(
                prepared, request.expectedPlanHash(), request.maxRows(), request.timeoutSeconds());
        return R.ok(toExecutionView(result, prepared.plan()));
    }

    private PreparedSemanticQuery confirm(SemanticQueryExecutionRequest request) {
        var binding = binding(request.datasetId(), request.semanticVersionId(), request.sourceId(), true);
        return executionService.confirm(request.question(), binding.versionId(), binding.sourceId(), request.context(),
                binding.access(), request.expectedPlanHash());
    }

    private record QueryBinding(String versionId, String sourceId, SemanticQueryDatasetAccess access) {}

    private QueryBinding binding(String datasetId, String versionId, String sourceId) {
        return binding(datasetId, versionId, sourceId, false);
    }

    private QueryBinding binding(String datasetId, String versionId, String sourceId, boolean confirmation) {
        if (datasetId == null || datasetId.isBlank()) {
            if (versionId == null || versionId.isBlank() || sourceId == null || sourceId.isBlank()) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "请选择可用数据集");
            }
            return new QueryBinding(versionId, sourceId, null);
        }
        var access = confirmation ? datasets.queryAccessForConfirmation(datasetId.trim()) : datasets.queryAccess(datasetId.trim());
        access.requireBinding(versionId == null || versionId.isBlank() ? access.versionId() : versionId,
                sourceId == null || sourceId.isBlank() ? access.sourceId() : sourceId);
        return new QueryBinding(access.versionId(), access.sourceId(), access);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private SemanticQueryPlanVO toPlanView(PreparedSemanticQuery prepared, String datasetId, String datasetName) {
        SemanticQueryInterpretation interpretation = prepared.interpretation();
        if (!prepared.ready()) {
            return new SemanticQueryPlanVO(
                    interpretation.status().name(), interpretation.question(), interpretation.semanticVersionId(),
                    interpretation.sourceId(), prepared.databaseType(), null, null, null, List.of(), List.of(),
                    List.of(), List.of(), interpretation.clarifications().stream().map(this::toClarification).toList(),
                    interpretation.reasons(), null, null, null, datasetId, datasetName);
        }
        var plan = prepared.plan();
        boolean hidePhysical = plan.exploratory();
        return new SemanticQueryPlanVO(
                interpretation.status().name(), interpretation.question(), interpretation.semanticVersionId(),
                interpretation.sourceId(), prepared.databaseType(), plan.planHash(), plan.query().language(),
                hidePhysical ? null : plan.query().statement(),
                hidePhysical ? List.of() : plan.query().parameters().stream().map(parameter -> parameter.name()).toList(),
                plan.query().columns().stream().map(column -> toColumn(column, hidePhysical)).toList(),
                hidePhysical ? List.of() : plan.query().lineage().stream().map(this::toLineage).toList(),
                plan.warnings(), List.of(), List.of(),
                plan.queryPath(), plan.routeReason(), interpretation.intent() == null ? null : toIntent(interpretation.intent(), hidePhysical),
                datasetId, datasetName);
    }

    private SemanticQueryExplainVO toExplainView(QueryExplainResult result, boolean hidePhysical) {
        return new SemanticQueryExplainVO(
                result.planHash(), result.language(), hidePhysical ? null : result.statement(),
                hidePhysical ? List.of() : result.parameterNames(),
                result.columns().stream().map(column -> toColumn(column, hidePhysical)).toList(),
                hidePhysical ? List.of() : result.lineage().stream().map(this::toLineage).toList(), result.warnings());
    }

    private SemanticQueryExecutionVO toExecutionView(QueryExecutionResult result, QueryPlan plan) {
        boolean hidePhysical = plan.exploratory();
        var evidence = result.validation();
        var validation = evidence == null ? null : new SemanticQueryValidationVO(evidence.technicalStatus().name(),
                evidence.resultStatus().name(), evidence.businessStatus().name(), evidence.businessReason(),
                evidence.checkedAt(), evidence.baselineLabel(), evidence.comparedRows(),
                evidence.missingReferenceGroups(), evidence.extraReferenceGroups(), evidence.differingGroups(),
                evidence.comparedWindow() == null ? null : new SemanticQueryValidationVO.QueryComparedWindow(
                        evidence.comparedWindow().from(), evidence.comparedWindow().to(),
                        evidence.comparedWindow().timeZone()));
        return new SemanticQueryExecutionVO(
                result.planHash(), result.rows().stream().map(this::decimalSafeRow).toList(),
                result.columns().stream().map(column -> toColumn(column, hidePhysical)).toList(),
                hidePhysical ? List.of() : result.lineage().stream().map(this::toLineage).toList(), result.rowCount(), result.truncated(),
                result.elapsedMs(), result.auditId(), result.warnings(), plan.queryPath(), validation);
    }

    private Map<String, Object> decimalSafeRow(Map<String, Object> row) {
        var safe = new LinkedHashMap<String, Object>();
        row.forEach((key, value) -> safe.put(key, value instanceof BigDecimal number
                && (number.precision() > 15 || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() == 0 && number.signum() != 0)
                ? number.toPlainString() : value));
        return safe;
    }

    private SemanticQueryPlanVO.ColumnVO toColumn(QueryColumn column, boolean hidePhysical) {
        return new SemanticQueryPlanVO.ColumnVO(
                column.role(), column.alias(), hidePhysical ? null : column.expression(), column.semanticIri());
    }

    private SemanticQueryPlanVO.LineageVO toLineage(QueryLineage lineage) {
        return new SemanticQueryPlanVO.LineageVO(
                lineage.semanticIri(), lineage.sourceId(), lineage.physicalObject(), lineage.physicalField());
    }

    private SemanticQueryInterpretVO toView(SemanticQueryInterpretation interpretation) {
        QueryIntent intent = interpretation.intent();
        return new SemanticQueryInterpretVO(
                interpretation.status().name(),
                interpretation.question(),
                interpretation.semanticVersionId(),
                interpretation.sourceId(),
                intent == null ? null : toIntent(intent),
                interpretation.clarifications().stream().map(this::toClarification).toList(),
                interpretation.reasons());
    }

    private SemanticQueryInterpretVO.QueryIntentVO toIntent(QueryIntent intent) {
        return toIntent(intent, false);
    }

    private SemanticQueryInterpretVO.QueryIntentVO toIntent(QueryIntent intent, boolean hidePhysical) {
        return new SemanticQueryInterpretVO.QueryIntentVO(
                intent.semanticVersionId(),
                intent.sourceId(),
                intent.metrics().stream().map(metric -> toMetric(metric, hidePhysical)).toList(),
                intent.dimensions().stream().map(dimension -> toDimension(dimension, hidePhysical)).toList(),
                intent.filters().stream().map(filter -> toFilter(filter, hidePhysical)).toList(),
                intent.timeRange() == null ? null : toTimeRange(intent.timeRange()),
                intent.limit(),
                toOutput(intent.output()));
    }

    private SemanticQueryInterpretVO.MetricVO toMetric(QueryMetric metric, boolean hidePhysical) {
        return new SemanticQueryInterpretVO.MetricVO(
                metric.iri(), metric.alias(), metric.aggregation(), hidePhysical ? null : metric.physicalObject(),
                hidePhysical ? null : metric.physicalField());
    }

    private SemanticQueryInterpretVO.DimensionVO toDimension(QueryDimension dimension, boolean hidePhysical) {
        return new SemanticQueryInterpretVO.DimensionVO(
                dimension.iri(), dimension.alias(), dimension.timeGrain(), hidePhysical ? null : dimension.physicalObject(),
                hidePhysical ? null : dimension.physicalField());
    }

    private SemanticQueryInterpretVO.FilterVO toFilter(QueryFilter filter, boolean hidePhysical) {
        return new SemanticQueryInterpretVO.FilterVO(
                filter.iri(), filter.alias(), filter.operator(), filter.value(), hidePhysical ? null : filter.physicalObject(),
                hidePhysical ? null : filter.physicalField());
    }

    private SemanticQueryInterpretVO.TimeRangeVO toTimeRange(QueryTimeRange range) {
        return new SemanticQueryInterpretVO.TimeRangeVO(
                range.fieldIri(), range.relativeDays(), range.from() == null ? null : range.from().toString(),
                range.to() == null ? null : range.to().toString());
    }

    private SemanticQueryInterpretVO.OutputVO toOutput(QueryOutput output) {
        return new SemanticQueryInterpretVO.OutputVO(output.view(), output.reason());
    }

    private SemanticQueryInterpretVO.ClarificationVO toClarification(SemanticQueryClarification clarification) {
        return new SemanticQueryInterpretVO.ClarificationVO(
                clarification.code(), clarification.question(), clarification.options(), clarification.candidateIris());
    }
}
