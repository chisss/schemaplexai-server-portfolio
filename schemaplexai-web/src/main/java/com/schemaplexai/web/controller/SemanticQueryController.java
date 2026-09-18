package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.semantic.SemanticQueryInterpretRequest;
import com.schemaplexai.model.vo.semantic.SemanticQueryInterpretVO;
import com.schemaplexai.service.semantic.application.orchestration.SemanticQueryApplicationService;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryClarification;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 自然语言语义解释 REST 入口，当前阶段不执行数据库查询。 */
@Validated
@RestController
@RequestMapping("/semantic/query")
@RequiredArgsConstructor
public class SemanticQueryController {

    private final SemanticQueryApplicationService queryService;

    @PostMapping("/interpret")
    @PreAuthorize("@authorizationService.hasPermission('semantic:query:interpret')")
    public R<SemanticQueryInterpretVO> interpret(@Valid @RequestBody SemanticQueryInterpretRequest request) {
        SemanticQueryInterpretation interpretation = queryService.interpret(
                request.question(), request.semanticVersionId(), request.sourceId());
        return R.ok(toView(interpretation));
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
