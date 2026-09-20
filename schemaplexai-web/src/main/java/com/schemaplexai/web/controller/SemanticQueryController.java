package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.semantic.SemanticQueryExecutionRequest;
import com.schemaplexai.model.dto.semantic.SemanticQueryInterpretRequest;
import com.schemaplexai.model.vo.semantic.SemanticQueryExecutionVO;
import com.schemaplexai.model.vo.semantic.SemanticQueryExplainVO;
import com.schemaplexai.model.vo.semantic.SemanticQueryInterpretVO;
import com.schemaplexai.model.vo.semantic.SemanticQueryPlanVO;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryApplicationService;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryExecutionApplicationService;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryLineage;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryClarification;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionResult;
import com.schemaplexai.service.semantic.domain.model.query.QueryExplainResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 自然语言语义解释、计划预览和受控只读执行 REST 入口。 */
@Validated
@RestController
@RequestMapping("/semantic/query")
@RequiredArgsConstructor
public class SemanticQueryController {

    private final SemanticQueryApplicationService queryService;
    private final SemanticQueryExecutionApplicationService executionService;

    @PostMapping("/interpret")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:interpret')")
    public R<SemanticQueryInterpretVO> interpret(@Valid @RequestBody SemanticQueryInterpretRequest request) {
        SemanticQueryInterpretation interpretation = queryService.interpret(
                request.question(), request.semanticVersionId(), request.sourceId(), request.context());
        return R.ok(toView(interpretation));
    }

    @PostMapping("/plan")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:interpret')")
    public R<SemanticQueryPlanVO> plan(@Valid @RequestBody SemanticQueryExecutionRequest request) {
        PreparedSemanticQuery prepared = prepare(request);
        return R.ok(toPlanView(prepared));
    }

    @PostMapping("/explain")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:execute')")
    public R<SemanticQueryExplainVO> explain(@Valid @RequestBody SemanticQueryExecutionRequest request) {
        PreparedSemanticQuery prepared = prepare(request);
        QueryExplainResult result = executionService.explain(
                prepared, request.expectedPlanHash(), request.maxRows(), request.timeoutSeconds());
        return R.ok(toExplainView(result));
    }

    @PostMapping("/execute")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:execute')")
    public R<SemanticQueryExecutionVO> execute(@Valid @RequestBody SemanticQueryExecutionRequest request) {
        PreparedSemanticQuery prepared = prepare(request);
        QueryExecutionResult result = executionService.execute(
                prepared, request.expectedPlanHash(), request.maxRows(), request.timeoutSeconds());
        return R.ok(toExecutionView(result));
    }

    private PreparedSemanticQuery prepare(SemanticQueryExecutionRequest request) {
        return executionService.prepare(
                request.question(), request.semanticVersionId(), request.sourceId(), request.context());
    }

    private SemanticQueryPlanVO toPlanView(PreparedSemanticQuery prepared) {
        SemanticQueryInterpretation interpretation = prepared.interpretation();
        if (!prepared.ready()) {
            return new SemanticQueryPlanVO(
                    interpretation.status().name(), interpretation.question(), interpretation.semanticVersionId(),
                    interpretation.sourceId(), prepared.databaseType(), null, null, null, List.of(), List.of(),
                    List.of(), List.of(), interpretation.clarifications().stream().map(this::toClarification).toList(),
                    interpretation.reasons());
        }
        var plan = prepared.plan();
        return new SemanticQueryPlanVO(
                interpretation.status().name(), interpretation.question(), interpretation.semanticVersionId(),
                interpretation.sourceId(), prepared.databaseType(), plan.planHash(), plan.query().language(),
                plan.query().statement(), plan.query().parameters().stream().map(parameter -> parameter.name()).toList(),
                plan.query().columns().stream().map(this::toColumn).toList(),
                plan.query().lineage().stream().map(this::toLineage).toList(), plan.warnings(), List.of(), List.of());
    }

    private SemanticQueryExplainVO toExplainView(QueryExplainResult result) {
        return new SemanticQueryExplainVO(
                result.planHash(), result.language(), result.statement(), result.parameterNames(),
                result.columns().stream().map(this::toColumn).toList(),
                result.lineage().stream().map(this::toLineage).toList(), result.warnings());
    }

    private SemanticQueryExecutionVO toExecutionView(QueryExecutionResult result) {
        return new SemanticQueryExecutionVO(
                result.planHash(), result.rows(), result.columns().stream().map(this::toColumn).toList(),
                result.lineage().stream().map(this::toLineage).toList(), result.rowCount(), result.truncated(),
                result.elapsedMs(), result.auditId(), result.warnings());
    }

    private SemanticQueryPlanVO.ColumnVO toColumn(QueryColumn column) {
        return new SemanticQueryPlanVO.ColumnVO(
                column.role(), column.alias(), column.expression(), column.semanticIri());
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
        return new SemanticQueryInterpretVO.QueryIntentVO(
                intent.semanticVersionId(),
                intent.sourceId(),
                intent.metrics().stream().map(this::toMetric).toList(),
                intent.dimensions().stream().map(this::toDimension).toList(),
                intent.filters().stream().map(this::toFilter).toList(),
                intent.timeRange() == null ? null : toTimeRange(intent.timeRange()),
                intent.limit(),
                toOutput(intent.output()));
    }

    private SemanticQueryInterpretVO.MetricVO toMetric(QueryMetric metric) {
        return new SemanticQueryInterpretVO.MetricVO(
                metric.iri(), metric.alias(), metric.aggregation(), metric.physicalObject(), metric.physicalField());
    }

    private SemanticQueryInterpretVO.DimensionVO toDimension(QueryDimension dimension) {
        return new SemanticQueryInterpretVO.DimensionVO(
                dimension.iri(), dimension.alias(), dimension.timeGrain(), dimension.physicalObject(), dimension.physicalField());
    }

    private SemanticQueryInterpretVO.FilterVO toFilter(QueryFilter filter) {
        return new SemanticQueryInterpretVO.FilterVO(
                filter.iri(), filter.alias(), filter.operator(), filter.value(), filter.physicalObject(), filter.physicalField());
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
