package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AutomaticDatasetQueryService {
    private static final int MAX_EXPLORATION_CANDIDATES = 8;
    private static final Set<Integer> UNAVAILABLE = Set.of(ResultCode.FORBIDDEN.getCode(),
            ResultCode.NOT_FOUND.getCode(), ResultCode.MCP_SERVER_NOT_FOUND.getCode(), ResultCode.SEMANTIC_VERSION_NOT_FOUND.getCode(),
            ResultCode.SEMANTIC_VERSION_STATUS_INVALID.getCode(), ResultCode.SEMANTIC_QUERY_SOURCE_UNAVAILABLE.getCode());
    private final SemanticQueryExecutionApplicationService queries;

    public record Candidate(String name, SemanticQueryDatasetAccess access) {}

    public record Resolution(Candidate candidate, PreparedSemanticQuery prepared) {}

    public Resolution planJointly(String question, String context, List<Candidate> granted,
                                  List<CompositeQueryPlanner.Part> parts) {
        var matches = new ArrayList<Resolution>();
        for (var candidate : scope(question, context, granted)) {
            var prepared = preparePublished(question, context, candidate);
            if (prepared != null && prepared.ready() && prepared.plan().structured()
                    && prepared.interpretation().intent().metrics().size() == parts.size()
                    && sharesScope(prepared.interpretation().intent(), parts, context, candidate)) {
                matches.add(new Resolution(candidate, prepared));
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean sharesScope(QueryIntent combined, List<CompositeQueryPlanner.Part> parts, String context,
                                Candidate candidate) {
        var metrics = new HashSet<>(combined.metrics());
        for (var part : parts) {
            var prepared = preparePublished(part.question(), context, candidate);
            if (prepared == null || !prepared.ready()) {
                return false;
            }
            var child = prepared.interpretation().intent();
            if (child.metrics().size() != 1 || !metrics.remove(child.metrics().getFirst())
                    || !child.dimensions().equals(combined.dimensions()) || !child.filters().equals(combined.filters())
                    || !Objects.equals(child.timeRange(), combined.timeRange())) {
                return false;
            }
        }
        return metrics.isEmpty();
    }

    public Resolution plan(String question, String context, List<Candidate> granted) {
        return plan(question, context, granted, message -> {});
    }

    public Resolution plan(String question, String context, List<Candidate> granted, Consumer<String> progress) {
        var candidates = scope(question, context, granted);
        var published = new ArrayList<Resolution>();
        var exploratory = new ArrayList<Resolution>();
        var clarifications = new ArrayList<Resolution>();
        for (var candidate : candidates) {
            var prepared = preparePublished(question, context, candidate);
            if (prepared == null) {
                continue;
            }
            var resolution = new Resolution(candidate, prepared);
            if (prepared.ready()) {
                published.add(resolution);
            } else if (prepared.interpretation().status() == QueryInterpretationStatus.CLARIFICATION) {
                clarifications.add(resolution);
            } else if (prepared.interpretation().status() == QueryInterpretationStatus.NO_MATCH
                    && candidate.access().canExplore()) {
                exploratory.add(resolution);
            }
        }
        if (!published.isEmpty()) {
            return choose(question, published);
        }
        if (exploratory.size() > MAX_EXPLORATION_CANDIDATES) {
            return clarify(question, "可用于 NL2SQL 的数据集较多，请补充业务主题或指标名称。");
        }
        var matches = new ArrayList<Resolution>();
        var explorationReasons = new LinkedHashSet<String>();
        if (!exploratory.isEmpty()) {
            progress.accept("未找到匹配的已发布口径，正在尝试 NL2SQL，仅使用已授权数据集及字段。");
        }
        for (var entry : exploratory) {
            var prepared = prepareExploration(question, context, entry, explorationReasons);
            if (prepared == null) {
                continue;
            }
            if (prepared.ready()) {
                matches.add(new Resolution(entry.candidate(), prepared));
                if (matches.size() > 1) {
                    return choose(question, matches);
                }
            } else {
                explorationReasons.addAll(prepared.interpretation().reasons());
            }
        }
        if (!matches.isEmpty()) {
            return matches.getFirst();
        }
        if (!clarifications.isEmpty()) {
            var clarification = choose(question, clarifications);
            if (exploratory.isEmpty()) {
                return clarification;
            }
            var interpretation = clarification.prepared().interpretation();
            var reasons = new ArrayList<>(interpretation.reasons());
            reasons.addAll(explorationFailure(explorationReasons));
            var updated = SemanticQueryInterpretation.clarification(question, interpretation.semanticVersionId(),
                    interpretation.sourceId(), interpretation.clarifications(), reasons);
            return new Resolution(clarification.candidate(), new PreparedSemanticQuery(updated, null, null, null));
        }
        var reasons = granted.isEmpty() ? List.of("当前账号没有可用的授权数据集。")
                : exploratory.isEmpty() ? List.of(candidates.stream().anyMatch(candidate -> candidate.access().canExplore())
                        ? "未找到匹配的已发布口径，也没有可用于这个问题的 NL2SQL 数据集。"
                        : "未命中已发布口径，当前可用数据集没有开放 NL2SQL。")
                : explorationFailure(explorationReasons);
        var result = new SemanticQueryInterpretation(QueryInterpretationStatus.NO_MATCH, question, null, "",
                null, List.of(), reasons);
        return new Resolution(null, new PreparedSemanticQuery(result, null, null, null));
    }

    private List<String> explorationFailure(Set<String> details) {
        var reasons = new ArrayList<String>();
        reasons.add("已检查可用于 NL2SQL 的授权数据集，未能生成可执行的查询计划。");
        details.stream().limit(3).forEach(reasons::add);
        return reasons;
    }

    private List<Candidate> scope(String question, String context, List<Candidate> granted) {
        String normalized = (question + " " + Objects.toString(context, "")).toLowerCase(Locale.ROOT);
        var explicit = granted.stream().filter(candidate -> normalized.contains(candidate.name().toLowerCase(Locale.ROOT)))
                .toList();
        return explicit.isEmpty() ? granted : explicit;
    }

    private PreparedSemanticQuery preparePublished(String question, String context, Candidate candidate) {
        try {
            return queries.preparePublished(question, context, candidate.access());
        } catch (BusinessException exception) {
            if (UNAVAILABLE.contains(exception.getCode())
                    || exception.getCode() == ResultCode.SEMANTIC_QUERY_NOT_INTERPRETABLE.getCode()) {
                return null;
            }
            throw exception;
        }
    }

    private PreparedSemanticQuery prepareExploration(String question, String context, Resolution entry,
                                                       Set<String> reasons) {
        try {
            return queries.prepareExploration(question, context, entry.candidate().access(),
                    entry.prepared().interpretation(), entry.candidate().name());
        } catch (BusinessException exception) {
            if (UNAVAILABLE.contains(exception.getCode())) {
                reasons.add("部分授权数据集的来源或 Schema 快照当前不可用。");
                return null;
            }
            if (exception.getCode() == ResultCode.AGENT_CONFIG_ERROR.getCode()) {
                reasons.add("NL2SQL 暂时无法调用文本模型。");
                return null;
            }
            throw exception;
        }
    }

    private Resolution choose(String question, List<Resolution> matches) {
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        String names = matches.stream().map(entry -> entry.candidate().name()).distinct().limit(5)
                .collect(Collectors.joining("、"));
        return clarify(question, "问题涉及多个已授权业务范围（" + names + "），请补充要查询的业务范围。");
    }

    private Resolution clarify(String question, String reason) {
        var result = SemanticQueryInterpretation.clarification(question, null, "", List.of(), List.of(reason));
        return new Resolution(null, new PreparedSemanticQuery(result, null, null, null));
    }
}
