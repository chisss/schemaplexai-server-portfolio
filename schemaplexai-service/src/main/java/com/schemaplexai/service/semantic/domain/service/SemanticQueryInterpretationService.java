package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.QueryCandidateRole;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryCandidate;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryClarification;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯领域语义解释器。第一阶段使用可审计的词法匹配，后续可替换为候选排序器，
 * 但输出仍必须经过同一结构化契约和映射校验。
 */
public final class SemanticQueryInterpretationService {

    private static final Pattern RECENT_DAYS = Pattern.compile("最近\\s*(\\d{1,3})\\s*天");
    private static final Set<String> AGGREGATIONS = Set.of("sum", "count", "avg", "min", "max");

    public SemanticQueryInterpretation interpret(
            String question,
            String semanticVersionId,
            String sourceId,
            List<SemanticQueryCandidate> candidates) {
        String normalizedQuestion = normalizeQuestion(question);
        List<SemanticQueryCandidate> usable = candidates == null ? List.of() : candidates.stream()
                .filter(candidate -> candidate.isMappedTo(sourceId))
                .toList();
        List<SemanticQueryClarification> clarifications = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        List<SemanticQueryCandidate> metrics = matches(normalizedQuestion, usable, QueryCandidateRole.METRIC);
        if (metrics.isEmpty()) {
            reasons.add("未识别到已映射指标");
            clarifications.add(new SemanticQueryClarification(
                    "METRIC_REQUIRED", "请指定要分析的指标", labels(usable, QueryCandidateRole.METRIC), iris(usable, QueryCandidateRole.METRIC)));
        } else if (metrics.size() > 1) {
            reasons.add("指标存在多个候选");
            clarifications.add(new SemanticQueryClarification(
                    "METRIC_AMBIGUOUS", "请确认要分析哪个指标", labels(metrics), iris(metrics)));
        }

        List<SemanticQueryCandidate> dimensions = matches(normalizedQuestion, usable, QueryCandidateRole.DIMENSION);
        if (dimensions.size() > 1) {
            reasons.add("维度存在多个候选");
            clarifications.add(new SemanticQueryClarification(
                    "DIMENSION_AMBIGUOUS", "请确认分组维度", labels(dimensions), iris(dimensions)));
        }

        List<SemanticQueryCandidate> timeCandidates = matches(normalizedQuestion, usable, QueryCandidateRole.TIME);
        if (timeCandidates.isEmpty() && RECENT_DAYS.matcher(normalizedQuestion).find()) {
            timeCandidates = usable.stream()
                    .filter(candidate -> candidate.role() == QueryCandidateRole.TIME)
                    .sorted(Comparator.comparing(SemanticQueryCandidate::iri))
                    .toList();
        }
        QueryTimeRange timeRange = parseTimeRange(normalizedQuestion, timeCandidates, clarifications);
        List<SemanticQueryCandidate> filters = matches(normalizedQuestion, usable, QueryCandidateRole.FILTER);
        if (filters.isEmpty() && (containsTerm(normalizedQuestion, "已完成") || containsTerm(normalizedQuestion, "完成"))) {
            filters = usable.stream()
                    .filter(candidate -> candidate.role() == QueryCandidateRole.FILTER)
                    .sorted(Comparator.comparing(SemanticQueryCandidate::iri))
                    .toList();
        }

        if (!clarifications.isEmpty()) {
            return SemanticQueryInterpretation.clarification(
                    normalizedQuestion, semanticVersionId, sourceId, clarifications, reasons);
        }

        SemanticQueryCandidate metric = metrics.get(0);
        QueryMetric queryMetric = new QueryMetric(
                metric.iri(), label(metric), aggregation(metric), metric.physicalObject(), metric.physicalField());
        List<QueryDimension> queryDimensions = dimensions.stream()
                .map(candidate -> new QueryDimension(
                        candidate.iri(), label(candidate), timeGrain(normalizedQuestion, candidate),
                        candidate.physicalObject(), candidate.physicalField()))
                .toList();
        List<QueryFilter> queryFilters = filters.stream()
                .map(candidate -> new QueryFilter(
                        candidate.iri(), label(candidate), "eq", filterValue(normalizedQuestion, candidate),
                        candidate.physicalObject(), candidate.physicalField()))
                .toList();
        QueryIntent intent = new QueryIntent(
                semanticVersionId,
                sourceId,
                List.of(queryMetric),
                queryDimensions,
                queryFilters,
                timeRange,
                200,
                new QueryOutput(timeRange != null ? "line" : "table", timeRange != null ? "时间序列指标" : "语义查询结果"));
        return SemanticQueryInterpretation.ready(normalizedQuestion, intent);
    }

    private List<SemanticQueryCandidate> matches(
            String question,
            List<SemanticQueryCandidate> candidates,
            QueryCandidateRole role) {
        return candidates.stream()
                .filter(candidate -> candidate.role() == role)
                .filter(candidate -> candidate.aliases().stream().anyMatch(alias -> containsTerm(question, alias)))
                .sorted(Comparator.comparing(SemanticQueryCandidate::iri))
                .distinct()
                .toList();
    }

    private QueryTimeRange parseTimeRange(
            String question,
            List<SemanticQueryCandidate> timeCandidates,
            List<SemanticQueryClarification> clarifications) {
        Matcher matcher = RECENT_DAYS.matcher(question);
        if (!matcher.find()) {
            return null;
        }
        if (timeCandidates.isEmpty()) {
            clarifications.add(new SemanticQueryClarification(
                    "TIME_FIELD_REQUIRED", "请确认时间字段", List.of(), List.of()));
            return null;
        }
        if (timeCandidates.size() > 1) {
            clarifications.add(new SemanticQueryClarification(
                    "TIME_FIELD_AMBIGUOUS", "请确认按哪个时间字段过滤", labels(timeCandidates), iris(timeCandidates)));
            return null;
        }
        int days = Integer.parseInt(matcher.group(1));
        SemanticQueryCandidate candidate = timeCandidates.get(0);
        return new QueryTimeRange(
                candidate.iri(), days, null, null, candidate.physicalObject(), candidate.physicalField());
    }

    private String aggregation(SemanticQueryCandidate candidate) {
        return candidate.aggregation() == null || !AGGREGATIONS.contains(candidate.aggregation().toLowerCase(Locale.ROOT))
                ? "sum" : candidate.aggregation().toLowerCase(Locale.ROOT);
    }

    private String filterValue(String question, SemanticQueryCandidate candidate) {
        if (containsTerm(question, "已完成") || containsTerm(question, "完成")) {
            return "completed";
        }
        return "true";
    }

    private String timeGrain(String question, SemanticQueryCandidate candidate) {
        if (containsTerm(question, "月")) {
            return "month";
        }
        if (containsTerm(question, "周")) {
            return "week";
        }
        if (containsTerm(question, "日") || containsTerm(question, "天")) {
            return "day";
        }
        return null;
    }

    private boolean containsTerm(String question, String term) {
        String normalized = term.trim().toLowerCase(Locale.ROOT);
        return !normalized.isBlank() && question.contains(normalized);
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        String normalized = question.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.length() > 2000) {
            throw new IllegalArgumentException("question must not exceed 2000 characters");
        }
        return normalized;
    }

    private String label(SemanticQueryCandidate candidate) {
        return candidate.aliases().isEmpty() ? candidate.iri() : candidate.aliases().get(0);
    }

    private List<String> labels(List<SemanticQueryCandidate> candidates, QueryCandidateRole role) {
        return labels(candidates.stream().filter(candidate -> candidate.role() == role).toList());
    }

    private List<String> labels(List<SemanticQueryCandidate> candidates) {
        return candidates.stream().map(this::label).distinct().limit(3).toList();
    }

    private List<String> iris(List<SemanticQueryCandidate> candidates, QueryCandidateRole role) {
        return iris(candidates.stream().filter(candidate -> candidate.role() == role).toList());
    }

    private List<String> iris(List<SemanticQueryCandidate> candidates) {
        return candidates.stream().map(SemanticQueryCandidate::iri).distinct().limit(3).toList();
    }
}
