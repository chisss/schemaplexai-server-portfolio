package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile.Time;
import com.schemaplexai.service.semantic.domain.model.query.QueryCalendar;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryClarification;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 从已发布词汇编译严格受限的单源查询意图。问题仍包含未识别约束时要求澄清。 */
public final class PublishedQueryInterpretationService {

    private static final int MAX_RANGE_DAYS = 366;
    private static final Pattern RELATIVE_DAYS = Pattern.compile("最近\\s*(\\d{1,3})\\s*天|(?:last|past)\\s+(\\d{1,3})\\s+days?");
    private static final Pattern DATE_RANGE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})\\s*(?:至|到|to|~|～)\\s*(\\d{4}-\\d{2}-\\d{2})");
    private static final Pattern BOILERPLATE = Pattern.compile(
            "补充上下文|麻烦|请帮我|请问|帮我|查询|查看|统计|分析|展示|显示|是多少|有多少|多少|的|请"
                    + "|\\b(?:show|find|give|me|what|is|the|was|calculate|and|where|from|for)\\b"
                    + "|[\\s,，。？?：:]+");
    private static final Map<String, Pattern> GRAINS = Map.of(
            "minute", grouping("分钟|minute"),
            "hour", grouping("小时|hour"),
            "day", grouping("日|天|day"),
            "week", grouping("周|星期|week"),
            "month", grouping("月度|月份|月|month"));
    private static final Map<String, String> AGGREGATION_WORDS = Map.of(
            "sum", "求和|总和|合计|总|sum|total",
            "count", "计数|数量|条数|count",
            "avg", "平均值|平均|avg|average",
            "min", "最小值|最小|min|minimum",
            "max", "最大值|最大|max|maximum");
    private static final Map<String, String> AGGREGATION_LABELS = Map.of(
            "sum", "总和", "count", "计数", "avg", "平均值", "min", "最小值", "max", "最大值");

    private final Clock clock;

    public PublishedQueryInterpretationService(Clock clock) {
        this.clock = clock;
    }

    public SemanticQueryInterpretation interpret(String question, String versionId, PublishedQueryProfile profile,
                                                 String releaseScope) {
        var input = new Question(question);
        if (!recognizesBusinessTerm(input, profile)) {
            return SemanticQueryInterpretation.noMatch(question, versionId, profile.sourceId());
        }
        try {
            List<QueryFilter> filters = filters(input, profile);
            QueryTimeRange range = timeRange(input, profile);
            List<QueryDimension> dimensions = dimensions(input, profile);
            List<QueryMetric> metrics = measures(input, profile);
            if (input.unmatched()) {
                throw new Clarify("UNSUPPORTED_CONSTRAINT", "存在未识别的筛选或计算条件，请按已发布口径描述问题");
            }
            var output = new QueryOutput(dimensions.stream().anyMatch(d -> d.calendar() != null) ? "line" : "table",
                    "已发布业务本体口径");
            return SemanticQueryInterpretation.ready(question, new QueryIntent(versionId, profile.sourceId(),
                    metrics, dimensions, filters, range, 200, output, releaseScope));
        } catch (Clarify clarification) {
            return SemanticQueryInterpretation.clarification(question, versionId, profile.sourceId(),
                    List.of(new SemanticQueryClarification(clarification.code, clarification.getMessage(), List.of(), List.of())),
                    List.of(clarification.getMessage()));
        }
    }

    private boolean recognizesBusinessTerm(Question input, PublishedQueryProfile profile) {
        return profile.measures().stream().anyMatch(measure -> input.contains(words(measure.label(), measure.name()))
                    || input.contains(qualifiedMeasure(measure)))
                || profile.dimensions().stream().anyMatch(dimension -> input.contains(words(dimension.label(), dimension.name())))
                || profile.filters().stream().anyMatch(filter -> input.contains(words(filter.label(), filter.name()))
                    || filter.values().stream().anyMatch(value -> input.contains(words(value.label()))))
                || profile.time() != null && input.contains(words(profile.time().label(), profile.time().name()));
    }

    private QueryMetric measure(Question input, PublishedQueryProfile profile) {
        var qualified = profile.measures().stream()
                .filter(measure -> input.contains(qualifiedMeasure(measure)))
                .toList();
        var matches = qualified.isEmpty() ? profile.measures().stream()
                .filter(measure -> input.contains(words(measure.label(), measure.name())))
                .toList() : qualified;
        if (matches.size() != 1) {
            throw matches.isEmpty()
                    ? new Clarify("METRIC_REQUIRED", "请指定已发布指标")
                    : new Clarify("METRIC_AMBIGUOUS", "命中多个指标口径，请明确要查询的指标");
        }
        var measure = matches.getFirst();
        input.consume(qualified.isEmpty() ? words(measure.label(), measure.name()) : qualifiedMeasure(measure));
        return metric(measure, profile);
    }

    private List<QueryMetric> measures(Question input, PublishedQueryProfile profile) {
        var pieces = input.remaining.split("\\s*(?:以及|和|及|与|、|\\band\\b)\\s*");
        if (pieces.length < 2 || pieces.length > 4) {
            return List.of(measure(input, profile));
        }
        var metrics = new ArrayList<QueryMetric>();
        for (String piece : pieces) {
            var term = new Question(piece);
            QueryMetric metric = measure(term, profile);
            if (term.unmatched() || metrics.contains(metric)) {
                throw new Clarify("METRIC_AMBIGUOUS", "请明确每项指标与共享的统计条件");
            }
            metrics.add(metric);
        }
        input.remaining = "";
        return List.copyOf(metrics);
    }

    private QueryMetric metric(PublishedQueryProfile.Measure measure, PublishedQueryProfile profile) {
        String label = measure.label() + "（" + AGGREGATION_LABELS.get(measure.aggregation()) + "）";
        return new QueryMetric(measure.iri(), label, measure.aggregation(), profile.physicalObject(),
                measure.physicalField());
    }

    private Pattern qualifiedMeasure(PublishedQueryProfile.Measure measure) {
        String field = wordAlternatives(measure.label(), measure.name());
        String aggregation = "(?:" + AGGREGATION_WORDS.get(measure.aggregation()) + ")";
        String qualified = aggregation + "\\s*" + field + "|" + field + "\\s*" + aggregation;
        String label = measure.label();
        if ("sum".equals(measure.aggregation()) && label.length() > 2 && label.endsWith("金额")) {
            String subject = label.substring(0, label.length() - 2).trim().toLowerCase(Locale.ROOT);
            qualified += "|" + Pattern.quote(subject) + "\\s*(?:总|合计)\\s*金额";
        }
        return Pattern.compile(qualified);
    }

    private List<QueryDimension> dimensions(Question input, PublishedQueryProfile profile) {
        var selected = new ArrayList<QueryDimension>();
        for (var dimension : profile.dimensions()) {
            if (input.consume(grouping(wordAlternatives(dimension.label(), dimension.name())))) {
                selected.add(new QueryDimension(dimension.iri(), dimension.label(), null,
                        profile.physicalObject(), dimension.physicalField()));
            }
        }
        var grains = GRAINS.entrySet().stream().filter(entry -> input.contains(entry.getValue())).toList();
        if (grains.isEmpty()) {
            return selected;
        }
        var time = profile.time();
        if (grains.size() != 1 || time == null || !time.grains().contains(grains.getFirst().getKey())) {
            throw new Clarify("TIME_GRAIN_UNAVAILABLE", "请明确使用一个已发布的时间粒度");
        }
        var entry = grains.getFirst();
        input.consume(entry.getValue());
        selected.add(new QueryDimension(time.iri(), time.label(), entry.getKey(), profile.physicalObject(),
                time.physicalField(), calendar(profile, time)));
        return selected;
    }

    private List<QueryFilter> filters(Question input, PublishedQueryProfile profile) {
        var resolved = new ArrayList<QueryFilter>();
        for (var filter : profile.filters()) {
            var values = filter.values().stream().filter(value ->
                    input.contains(filterPattern(filter.label(), filter.name(), value.label()))).toList();
            if (values.size() > 1) {
                throw new Clarify("FILTER_VALUE_AMBIGUOUS", "筛选条件命中多个值，请明确使用一个已发布选项");
            }
            if (values.isEmpty()) {
                continue;
            }
            var value = values.getFirst();
            input.consume(filterPattern(filter.label(), filter.name(), value.label()));
            resolved.add(new QueryFilter(filter.iri(), filter.label(), "eq", value.value(),
                    profile.physicalObject(), filter.physicalField(), true));
        }
        return resolved;
    }

    private QueryTimeRange timeRange(Question input, PublishedQueryProfile profile) {
        var relative = RELATIVE_DAYS.matcher(input.remaining);
        var dates = DATE_RANGE.matcher(input.remaining);
        if (relative.find()) {
            requireTime(profile.time());
            String daysText = relative.group(1) == null ? relative.group(2) : relative.group(1);
            int days = Integer.parseInt(daysText);
            if (days < 1 || days > MAX_RANGE_DAYS || relative.find() || dates.find()) {
                throw invalidRange();
            }
            var today = LocalDate.now(clock.withZone(ZoneId.of(profile.timeZone())));
            input.consume(RELATIVE_DAYS);
            return range(profile, today.minusDays(days - 1L), today);
        }
        if (!dates.find()) {
            return null;
        }
        requireTime(profile.time());
        try {
            var first = LocalDate.parse(dates.group(1));
            var last = LocalDate.parse(dates.group(2));
            long days = ChronoUnit.DAYS.between(first, last) + 1;
            if (days < 1 || days > MAX_RANGE_DAYS || dates.find() || first.getYear() == 0 || last.getYear() == 9999) {
                throw invalidRange();
            }
            input.consume(DATE_RANGE);
            return range(profile, first, last);
        } catch (DateTimeException ex) {
            throw invalidRange();
        }
    }

    private QueryTimeRange range(PublishedQueryProfile profile, LocalDate first, LocalDate last) {
        var time = profile.time();
        return new QueryTimeRange(time.iri(), null, first, last, profile.physicalObject(), time.physicalField(),
                calendar(profile, time));
    }

    private QueryCalendar calendar(PublishedQueryProfile profile, Time time) {
        return new QueryCalendar(profile.timeZone(), time.physicalType());
    }

    private void requireTime(Time time) {
        if (time == null) {
            throw new Clarify("TIME_FIELD_REQUIRED", "当前查询口径未发布时间字段，请移除时间条件");
        }
    }

    private Clarify invalidRange() {
        return new Clarify("INVALID_TIME_RANGE", "请提供连续且不超过 366 天的日期范围");
    }

    private static Pattern words(String... labels) {
        return Pattern.compile(wordAlternatives(labels));
    }

    private static String wordAlternatives(String... labels) {
        return Stream.of(labels)
                .map(label -> label.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "))
                .filter(label -> !label.isBlank()).distinct()
                .sorted((left, right) -> Integer.compare(right.length(), left.length()))
                .map(Pattern::quote)
                .collect(Collectors.joining("|", "(?<![a-z0-9_])(?:", ")(?![a-z0-9_])"));
    }

    private static Pattern grouping(String term) {
        return Pattern.compile("(?:按\\s*|\\bgroup\\s+by\\s+)(?:" + term
                + ")(?:\\s*分组)?");
    }

    private static Pattern filterPattern(String label, String name, String valueLabel) {
        return Pattern.compile("(?:仅看|只看|仅|只)?\\s*" + wordAlternatives(label, name)
                + "\\s*(?:等于|为|是|=)\\s*" + wordAlternatives(valueLabel));
    }

    private static final class Question {
        private String remaining;

        private Question(String text) {
            remaining = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        }

        private boolean contains(Pattern pattern) {
            return pattern.matcher(remaining).find();
        }

        private boolean consume(Pattern pattern) {
            var matcher = pattern.matcher(remaining);
            if (!matcher.find()) {
                return false;
            }
            remaining = matcher.replaceAll(" ");
            return true;
        }

        private boolean unmatched() {
            return !BOILERPLATE.matcher(remaining).replaceAll("").isEmpty();
        }
    }

    private static final class Clarify extends RuntimeException {
        private final String code;

        private Clarify(String code, String message) {
            super(message);
            this.code = code;
        }
    }
}
