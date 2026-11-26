package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.semantic.domain.model.query.PublishedQueryReference;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/** 候选与独立参考在一个 PostgreSQL 只读语句快照中分组和比较。 */
final class PostgresReferenceProjection {
    static final String STATUS_COLUMN = "__spx_reference_status";
    static final String SUMMARY_COLUMN = "__spx_reference_summary";
    static final String MISSING_COLUMN = "__spx_missing_reference_groups";
    static final String EXTRA_COLUMN = "__spx_extra_reference_groups";
    static final String DIFFERING_COLUMN = "__spx_differing_groups";
    static final String COMPARED_COLUMN = "__spx_compared_rows";
    static final String CANDIDATE_ROWS_COLUMN = "__spx_candidate_rows";
    static final String REFERENCE_ROWS_COLUMN = "__spx_reference_rows";
    static final String REFERENCE_VALID_COLUMN = "__spx_reference_valid";
    static final int MAX_GROUPS = 200;
    static final int GROUP_PROBE_LIMIT = MAX_GROUPS + 1;
    static final int OUTPUT_LIMIT = GROUP_PROBE_LIMIT + 1;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");

    String addReference(QueryPlan plan, String candidate) {
        PublishedQueryReference reference = plan.reference();
        validatePlan(plan, reference);
        String[] view = reference.referenceView().split("\\.", -1);
        if (view.length != 2 || reference.referenceView().equals(reference.physicalObject())
                || reference.tolerance().signum() < 0 || reference.tolerance().precision() > 38
                || reference.tolerance().scale() > 18) {
            throw new IllegalArgumentException("业务对账引用无效");
        }

        var columns = plan.query().columns();
        var dimensions = columns.stream().filter(column -> "dimension".equals(column.role())).toList();
        boolean singleton = reference.dimensions().isEmpty() && reference.time() == null;
        StringBuilder sql = new StringBuilder("WITH candidate AS (").append(candidate)
                .append("), reference_gate AS (");
        appendReferenceGate(sql, reference, view);
        sql.append("), reference_groups AS (");
        appendReferenceGroups(sql, plan, reference, view, singleton);
        sql.append("""
                ), reference_summary AS (
                    SELECT COUNT(*) AS group_count,
                           COUNT(*) FILTER (WHERE CAST(__reference_metric AS text) IN ('NaN', 'Infinity', '-Infinity')) AS non_finite
                    FROM reference_groups
                ), scored AS (
                    SELECT c.*, CASE
                """).append(groupStatus(reference.tolerance()))
                .append(" END AS ").append(STATUS_COLUMN)
                .append(" FROM candidate c LEFT JOIN reference_groups r ON ");
        if (singleton) {
            sql.append("r.__reference_present AND (SELECT group_count FROM reference_summary) = 1");
        } else {
            appendGroupMatch(sql, dimensions, "c", "r");
        }
        sql.append("""
                ), summary AS (
                    SELECT COUNT(*) AS candidate_rows,
                           COUNT(*) FILTER (WHERE %1$s = 'MISSING_REFERENCE') AS missing_groups,
                           COUNT(*) FILTER (WHERE %1$s = 'MISMATCH') AS differing_groups,
                           COUNT(*) FILTER (WHERE %1$s <> 'MISSING_REFERENCE') AS compared_groups
                    FROM scored
                ), output_rows AS (
                    SELECT %2$s, s.%1$s, false AS %3$s,
                           NULL AS %4$s, NULL AS %5$s, NULL AS %6$s, NULL AS %7$s,
                           NULL AS %8$s, NULL AS %9$s, NULL AS %10$s
                    FROM scored s
                    UNION ALL
                    SELECT %11$s, 'SUMMARY', true, summary.missing_groups,
                           GREATEST(reference_summary.group_count - summary.compared_groups, 0),
                           summary.differing_groups, summary.compared_groups,
                           summary.candidate_rows, reference_summary.group_count,
                           reference_gate.valid AND reference_summary.non_finite = 0%12$s
                    FROM summary CROSS JOIN reference_summary CROSS JOIN reference_gate
                ) SELECT * FROM output_rows ORDER BY %3$s
                """.formatted(STATUS_COLUMN, columns.stream().map(column -> "s." + column.alias())
                        .collect(Collectors.joining(", ")), SUMMARY_COLUMN, MISSING_COLUMN, EXTRA_COLUMN,
                        DIFFERING_COLUMN, COMPARED_COLUMN, CANDIDATE_ROWS_COLUMN, REFERENCE_ROWS_COLUMN,
                        REFERENCE_VALID_COLUMN, columns.stream().map(column -> "NULL")
                                .collect(Collectors.joining(", ")),
                        singleton ? " AND reference_summary.group_count = 1" : ""));
        dimensions.forEach(column -> sql.append(", ").append(column.alias()));
        return sql.append(" LIMIT ").append(OUTPUT_LIMIT).toString();
    }

    private void appendReferenceGroups(StringBuilder sql, QueryPlan plan, PublishedQueryReference reference,
                                       String[] view, boolean singleton) {
        String metric = "approved." + quote(reference.referenceColumn());
        sql.append("SELECT ").append(singleton ? "CAST(" + metric + " AS numeric)"
                : aggregate(reference.aggregation(), metric))
                .append(" AS __reference_metric, true AS __reference_present");
        for (int index = 0; index < reference.dimensions().size(); index++) {
            sql.append(", approved.").append(quote(reference.dimensions().get(index).referenceColumn()))
                    .append(" AS dimension_").append(index + 1);
        }
        sql.append(" FROM ").append(quote(view[0])).append('.').append(quote(view[1]))
                .append(" approved WHERE (SELECT valid FROM reference_gate)");
        if (plan.query().timeRange() != null) {
            var time = reference.time();
            String column = "approved." + quote(time.referenceColumn());
            var parameters = plan.query().parameters();
            sql.append(new QueryParameterBinder().bind(" AND " + column + " >= CAST(:p_time_from AS "
                    + time.physicalType() + ") AND " + column + " < CAST(:p_time_to AS " + time.physicalType()
                    + ")", parameters));
        }
        if (!reference.dimensions().isEmpty()) {
            sql.append(" GROUP BY ").append(reference.dimensions().stream()
                    .map(dimension -> "approved." + quote(dimension.referenceColumn()))
                    .collect(Collectors.joining(", ")));
        } else if (!singleton) {
            sql.append(" HAVING (SELECT valid FROM reference_gate)");
        }
        sql.append(" LIMIT ").append(singleton ? 2 : GROUP_PROBE_LIMIT);
    }

    private void appendReferenceGate(StringBuilder sql, PublishedQueryReference reference, String[] view) {
        sql.append("SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_class relation ")
                .append("JOIN pg_catalog.pg_namespace ns ON ns.oid=relation.relnamespace ")
                .append("WHERE ns.nspname=").append(literal(view[0]))
                .append(" AND relation.relname=").append(literal(view[1]))
                .append(" AND relation.relkind='v' AND 'security_invoker=true'=ANY(relation.reloptions)")
                .append(" AND ");
        appendCatalogColumn(sql, view, reference.referenceColumn(), null);
        for (var dimension : reference.dimensions()) {
            sql.append(" AND ");
            appendCatalogColumn(sql, view, dimension.referenceColumn(), dimension.physicalType());
        }
        if (reference.time() != null) {
            sql.append(" AND ");
            appendCatalogColumn(sql, view, reference.time().referenceColumn(), reference.time().physicalType());
        }
        sql.append(") AS valid");
    }

    private void appendCatalogColumn(StringBuilder sql, String[] view, String column, String physicalType) {
        sql.append("EXISTS (SELECT 1 FROM pg_catalog.pg_class rel ")
                .append("JOIN pg_catalog.pg_namespace ns ON ns.oid=rel.relnamespace ")
                .append("JOIN pg_catalog.pg_attribute field ON field.attrelid=rel.oid ")
                .append("JOIN pg_catalog.pg_type typ ON typ.oid=field.atttypid ")
                .append("JOIN pg_catalog.pg_namespace type_ns ON type_ns.oid=typ.typnamespace ")
                .append("WHERE ns.nspname=").append(literal(view[0]))
                .append(" AND rel.relname=").append(literal(view[1]))
                .append(" AND field.attname=").append(literal(column))
                .append(" AND field.attnum>0 AND NOT field.attisdropped AND type_ns.nspname='pg_catalog'");
        if (physicalType == null) {
            sql.append(" AND typ.typname IN ('int2','int4','int8','numeric')");
        } else {
            String names = referenceTypeNames(physicalType);
            if (names == null) {
                sql.append(" AND false");
            } else {
                sql.append(" AND typ.typname IN (").append(names).append(")");
            }
        }
        sql.append(')');
    }

    private String referenceTypeNames(String type) {
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "smallint" -> "'int2'";
            case "integer", "int" -> "'int4'";
            case "bigint" -> "'int8'";
            case "numeric", "decimal" -> "'numeric'";
            case "character varying", "varchar" -> "'varchar'";
            case "character", "char" -> "'bpchar'";
            case "text" -> "'text'";
            case "boolean", "bool" -> "'bool'";
            case "date" -> "'date'";
            case "timestamp without time zone", "timestamp" -> "'timestamp'";
            case "timestamp with time zone", "timestamptz" -> "'timestamptz'";
            default -> null;
        };
    }

    private String literal(String text) {
        return "'" + text.replace("'", "''") + "'";
    }

    private void appendGroupMatch(StringBuilder sql, List<QueryColumn> dimensions, String left, String right) {
        if (dimensions.isEmpty()) {
            sql.append("true");
            return;
        }
        sql.append(IntStream.range(0, dimensions.size())
                .mapToObj(index -> left + "." + dimensions.get(index).alias() + " IS NOT DISTINCT FROM "
                        + right + ".dimension_" + (index + 1))
                .collect(Collectors.joining(" AND ")));
    }

    private String groupStatus(BigDecimal tolerance) {
        return """
                WHEN NOT coalesce(r.__reference_present,false) THEN 'MISSING_REFERENCE'
                WHEN c.metric_1 IS NULL AND r.__reference_metric IS NULL THEN 'MATCHED'
                WHEN c.metric_1 IS NULL OR r.__reference_metric IS NULL THEN 'MISMATCH'
                WHEN abs(CAST(c.metric_1 AS numeric)-r.__reference_metric) > %s THEN 'MISMATCH'
                ELSE 'MATCHED'
                """.formatted(tolerance.toPlainString());
    }

    private String aggregate(String aggregation, String column) {
        return switch (aggregation) {
            case "sum" -> "CAST(SUM(" + column + ") AS numeric)";
            case "avg" -> "CAST(AVG(" + column + ") AS numeric)";
            case "count" -> "CAST(COUNT(" + column + ") AS numeric)";
            case "min" -> "CAST(MIN(" + column + ") AS numeric)";
            case "max" -> "CAST(MAX(" + column + ") AS numeric)";
            default -> throw new IllegalArgumentException("参考指标聚合无效");
        };
    }

    private void validatePlan(QueryPlan plan, PublishedQueryReference reference) {
        var columns = plan.query().columns();
        long metricCount = columns.stream().filter(column -> "metric".equals(column.role())).count();
        var outputDimensions = columns.stream().filter(column -> "dimension".equals(column.role())).toList();
        var dimensions = plan.query().dimensions();
        if (!plan.structured() || plan.exploratory() || metricCount != 1 || dimensions.size() > 3
                || columns.size() != dimensions.size() + 1 || outputDimensions.size() != dimensions.size()
                || reference.dimensions().size() != dimensions.size()
                || !validWindow(plan)
                || !matchesMetric(plan, reference)) {
            throw new IllegalArgumentException("业务对账需要单指标及完整登记的维度，不支持额外筛选或探索计划");
        }
        if (dimensions.stream().anyMatch(dimension -> dimension.timeGrain() != null
                || dimension.calendar() != null)
                || outputDimensions.stream().anyMatch(dimension -> dimension.expression().contains("date_trunc(")
                || dimension.expression().startsWith("TO_CHAR("))) {
            throw new IllegalArgumentException("对账不支持日、周、月时间粒度分桶");
        }
        for (int index = 0; index < dimensions.size(); index++) {
            var configured = reference.dimensions().get(index);
            var requested = dimensions.get(index);
            if (!configured.iri().equals(requested.iri())
                    || !configured.physicalField().equals(requested.physicalField())
                    || !reference.physicalObject().equals(requested.physicalObject())
                    || !outputDimensions.get(index).alias().equals("dimension_" + (index + 1))
                    || !configured.iri().equals(outputDimensions.get(index).semanticIri())
                    || !IDENTIFIER.matcher(configured.referenceColumn()).matches()) {
                throw new IllegalArgumentException("计划维度与参考视图绑定不一致");
            }
        }
        QueryTimeRange range = plan.query().timeRange();
        if (range != null && (reference.time() == null || range.calendar() == null
                || !reference.time().iri().equals(range.fieldIri())
                || !reference.time().physicalField().equals(range.physicalField())
                || !reference.physicalObject().equals(range.physicalObject())
                || !reference.time().timeZone().equals(range.calendar().timeZone())
                || !reference.time().physicalType().equals(range.calendar().physicalType()))) {
            throw new IllegalArgumentException("计划窗口没有已登记的本体时间角色");
        }
    }

    private boolean validWindow(QueryPlan plan) {
        QueryTimeRange range = plan.query().timeRange();
        var parameters = plan.query().parameters().stream().map(parameter -> parameter.name()).toList();
        if (range == null) {
            return parameters.isEmpty();
        }
        return range.relativeDays() == null && range.from() != null && range.to() != null
                && range.to().toEpochDay() - range.from().toEpochDay() < 366
                && parameters.equals(List.of("p_time_from", "p_time_to"));
    }

    private boolean matchesMetric(QueryPlan plan, PublishedQueryReference reference) {
        var metric = plan.query().columns().stream().filter(column -> "metric".equals(column.role())).findFirst().orElse(null);
        var lineage = plan.query().lineage().stream().filter(line -> reference.measureIri().equals(line.semanticIri()))
                .findFirst().orElse(null);
        return metric != null && lineage != null && "metric_1".equals(metric.alias())
                && reference.measureIri().equals(metric.semanticIri())
                && Objects.equals(reference.versionId(), plan.semanticVersionId())
                && reference.sourceId().equals(lineage.sourceId())
                && reference.physicalObject().equals(lineage.physicalObject())
                && Objects.equals(reference.measureColumn(), lineage.physicalField());
    }

    private String quote(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) throw new IllegalArgumentException("参考字段名无效");
        return "\"" + identifier + "\"";
    }
}
