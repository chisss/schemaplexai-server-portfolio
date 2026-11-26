package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.BusinessStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.ComparedWindow;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.ResultStatus;
import com.schemaplexai.service.semantic.domain.model.query.QueryResultValidation.TechnicalStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 对严格 SQL 路径的行边界、标量、结果分组键和业务证据做验证。 */
final class StructuredQueryResultValidator {
    private static final long MAX_VALUE_BYTES = 1_000_000;
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d+(?:\\.\\d+)?(?:[Ee][+-]?\\d+)?");
    private static final int MAX_METRIC_CHARS = 1024;
    private static final Set<String> GROUPED_REFERENCE_COLUMNS = Set.of(
            PostgresReferenceProjection.STATUS_COLUMN, PostgresReferenceProjection.SUMMARY_COLUMN,
            PostgresReferenceProjection.MISSING_COLUMN, PostgresReferenceProjection.EXTRA_COLUMN,
            PostgresReferenceProjection.DIFFERING_COLUMN, PostgresReferenceProjection.COMPARED_COLUMN,
            PostgresReferenceProjection.CANDIDATE_ROWS_COLUMN, PostgresReferenceProjection.REFERENCE_ROWS_COLUMN,
            PostgresReferenceProjection.REFERENCE_VALID_COLUMN);

    record CheckedRows(List<Map<String, Object>> rows, QueryResultValidation validation, int rowCount, boolean truncated) {}

    CheckedRows validate(QueryPlan plan, DatabaseQueryResultVO result, int limit) {
        var input = result.getRows();
        if (plan.reference() != null) {
            return validateGroups(plan, result, limit);
        }
        if (input == null || result.getRowCount() == null || result.getTruncated() == null
                || input.size() > limit || (result.getTruncated()
                ? input.size() != limit || result.getRowCount() <= input.size()
                : result.getRowCount() != input.size())) {
            throw invalid("数据源返回的结果行数与读取边界不一致");
        }
        Set<String> aliases = plan.query().columns().stream().map(QueryColumn::alias).collect(Collectors.toSet());
        if (aliases.isEmpty() || aliases.size() != plan.query().columns().size()) {
            throw invalid("已确认查询计划的结果列无效");
        }
        List<String> dimensions = plan.query().columns().stream().filter(column -> "dimension".equals(column.role()))
                .map(QueryColumn::alias).toList();
        var columns = result.getColumns();
        if (!input.isEmpty() && (columns == null || columns.size() != aliases.size() || !new HashSet<>(columns).equals(aliases))) {
            throw invalid("数据源返回的结果列与计划不一致");
        }
        var rows = validateRows(plan, input, aliases, dimensions);
        String reason = plan.exploratory() ? "探索查询不适用业务对账" : "当前查询无适用的业务对账基准";
        var evidence = new QueryResultValidation(TechnicalStatus.PASSED, resultStatus(rows.size(), result.getTruncated()),
                BusinessStatus.NOT_CHECKED, reason, Instant.now(), null, null);
        return new CheckedRows(rows, evidence, result.getRowCount(), result.getTruncated());
    }

    private CheckedRows validateGroups(QueryPlan plan, DatabaseQueryResultVO result, int limit) {
        var input = result.getRows();
        if (input == null || !Boolean.FALSE.equals(result.getTruncated()) || input.stream().anyMatch(row -> row == null)
                || input.size() > PostgresReferenceProjection.OUTPUT_LIMIT
                || !Integer.valueOf(input.size()).equals(result.getRowCount())) {
            throw invalid("分组对账查询输出超出源端结果边界");
        }
        var aliases = plan.query().columns().stream().map(QueryColumn::alias).collect(Collectors.toSet());
        var keys = new HashSet<>(aliases);
        keys.addAll(GROUPED_REFERENCE_COLUMNS);
        if (result.getColumns() == null || !new HashSet<>(result.getColumns()).equals(keys)
                || result.getColumns().size() != keys.size() || input.stream().anyMatch(row -> !row.keySet().equals(keys))) {
            throw invalid("分组对账结果列与计划不一致");
        }
        var summaryRows = input.stream()
                .filter(row -> Boolean.TRUE.equals(row.get(PostgresReferenceProjection.SUMMARY_COLUMN))).toList();
        if (summaryRows.size() != 1) {
            throw invalid("分组对账摘要缺失或重复");
        }
        var summary = summaryRows.getFirst();
        if (!"SUMMARY".equals(summary.get(PostgresReferenceProjection.STATUS_COLUMN))
                || !(summary.get(PostgresReferenceProjection.REFERENCE_VALID_COLUMN) instanceof Boolean referenceValid)
                || aliases.stream().anyMatch(alias -> summary.get(alias) != null)) {
            throw invalid("分组对账摘要格式无效");
        }
        var candidateRows = input.stream()
                .filter(row -> !Boolean.TRUE.equals(row.get(PostgresReferenceProjection.SUMMARY_COLUMN))).toList();
        return compareGroups(plan, limit, keys, summary, candidateRows, referenceValid);
    }

    private CheckedRows compareGroups(QueryPlan plan, int limit, Set<String> keys,
                                      Map<String, Object> summary, List<Map<String, Object>> candidateRows,
                                      boolean referenceValid) {
        int count = count(summary.get(PostgresReferenceProjection.CANDIDATE_ROWS_COLUMN));
        int referenceCount = count(summary.get(PostgresReferenceProjection.REFERENCE_ROWS_COLUMN));
        int missing = count(summary.get(PostgresReferenceProjection.MISSING_COLUMN));
        int extra = count(summary.get(PostgresReferenceProjection.EXTRA_COLUMN));
        int differing = count(summary.get(PostgresReferenceProjection.DIFFERING_COLUMN));
        int compared = count(summary.get(PostgresReferenceProjection.COMPARED_COLUMN));
        int matched = 0;
        int candidateMissing = 0;
        int candidateDiffering = 0;
        for (var row : candidateRows) {
            if (!Boolean.FALSE.equals(row.get(PostgresReferenceProjection.SUMMARY_COLUMN))
                    || !(row.get(PostgresReferenceProjection.STATUS_COLUMN) instanceof String status)) {
                throw invalid("分组对账行格式无效");
            }
            switch (status) {
                case "MATCHED" -> matched++;
                case "MISSING_REFERENCE" -> candidateMissing++;
                case "MISMATCH" -> candidateDiffering++;
                default -> throw invalid("源端返回了无法识别的对账状态");
            }
        }
        if (count != candidateRows.size() || missing != candidateMissing || differing != candidateDiffering
                || compared != matched + differing || referenceCount != compared + extra) {
            throw invalid("逐组对账状态与摘要计数不一致");
        }

        var dimensions = plan.query().columns().stream().filter(column -> "dimension".equals(column.role()))
                .map(QueryColumn::alias).toList();
        var rows = validateRows(plan, candidateRows, keys, dimensions);
        boolean truncated = count > limit;
        String unavailable = referenceUnavailable(referenceValid, referenceCount, truncated);
        BusinessStatus business = BusinessStatus.NOT_CHECKED;
        if (unavailable == null) {
            business = missing + extra + differing == 0 ? BusinessStatus.MATCHED : BusinessStatus.MISMATCH;
        }

        var time = plan.query().timeRange();
        var window = time == null ? null
                : new ComparedWindow(time.from().toString(), time.to().toString(), time.calendar().timeZone());
        String reason = business == BusinessStatus.MISMATCH ? "分组或指标结果超出参考范围，请复核" : unavailable;
        boolean showGroupCounts = unavailable == null && (!dimensions.isEmpty() || plan.reference().time() != null);
        var evidence = new QueryResultValidation(TechnicalStatus.PASSED, resultStatus(count, truncated), business, reason,
                Instant.now(), plan.reference().label(), unavailable == null ? compared : null,
                showGroupCounts ? missing : null, showGroupCounts ? extra : null,
                showGroupCounts ? differing : null, window);
        return new CheckedRows(truncated ? rows.subList(0, limit) : rows, evidence, count, truncated);
    }

    private String referenceUnavailable(boolean valid, int referenceCount, boolean truncated) {
        if (!valid) {
            return "参考基准不满足视图安全模式、字段类型、有限数值或单行要求";
        }
        if (referenceCount > PostgresReferenceProjection.MAX_GROUPS) {
            return "参考结果超过 200 个分组";
        }
        return truncated ? "候选结果超过本次查询的返回上限" : null;
    }

    private ResultStatus resultStatus(int count, boolean truncated) {
        return truncated ? ResultStatus.TRUNCATED : count == 0 ? ResultStatus.EMPTY : ResultStatus.COMPLETE;
    }

    private int count(Object value) {
        if (!(value instanceof Number number)) throw invalid("分组对账摘要计数格式无效");
        try {
            int count = new BigDecimal(number.toString()).intValueExact();
            if (count < 0 || count > PostgresReferenceProjection.GROUP_PROBE_LIMIT) throw invalid("分组对账摘要计数超出安全范围");
            return count;
        } catch (NumberFormatException | ArithmeticException exception) {
            throw invalid("分组对账摘要计数格式无效");
        }
    }

    private List<Map<String, Object>> validateRows(QueryPlan plan, List<Map<String, Object>> input,
                                                  Set<String> allowedKeys, List<String> dimensions) {
        var keys = new HashSet<List<Object>>();
        var validated = new ArrayList<Map<String, Object>>(input.size());
        long bytes = 0;
        for (var row : input) {
            if (row == null || !allowedKeys.equals(row.keySet())) {
                throw invalid("数据源返回的结果列与计划不一致");
            }
            var copied = new LinkedHashMap<String, Object>();
            for (var column : plan.query().columns()) {
                Object value = row.get(column.alias());
                Object canonical = "metric".equals(column.role()) ? numeric(value) : scalar(value);
                bytes += length(column.alias()) + length(canonical);
                if (bytes > MAX_VALUE_BYTES) {
                    throw invalid("查询结果内容超出大小限制");
                }
                copied.put(column.alias(), canonical);
            }
            if (!keys.add(dimensions.stream().map(name -> groupKey(copied.get(name))).toList())) {
                throw invalid("查询结果包含重复的分组键");
            }
            validated.add(copied);
        }
        return validated;
    }

    private Object groupKey(Object value) {
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value;
    }

    private Object scalar(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Boolean flag -> flag;
            case Number number -> numeric(number);
            default -> throw invalid("数据源返回了意外的非标量结果");
        };
    }

    private BigDecimal numeric(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number) && !(value instanceof String)) {
            throw invalid("数据源返回的指标不是数值");
        }
        String text = value.toString();
        if (text.length() > MAX_METRIC_CHARS || !DECIMAL.matcher(text).matches()) {
            throw invalid("数据源返回的指标无法无损表示为有限十进制");
        }
        try {
            var number = new BigDecimal(text);
            if (Math.abs((long) number.scale()) > MAX_METRIC_CHARS || number.precision() > MAX_METRIC_CHARS) {
                throw invalid("数据源返回的指标超出精度限制");
            }
            return number;
        } catch (NumberFormatException exception) {
            throw invalid("数据源返回的指标无法无损表示为有限十进制");
        }
    }

    private long length(Object value) {
        return value == null ? 4 : value.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private BusinessException invalid(String reason) {
        return new BusinessException(ResultCode.SEMANTIC_QUERY_EXECUTION_UNAVAILABLE, reason);
    }
}
