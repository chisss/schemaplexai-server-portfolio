package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.CompiledQuery;
import com.schemaplexai.service.semantic.domain.model.query.QueryCalendar;
import com.schemaplexai.service.semantic.domain.model.query.QueryColumn;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryLineage;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryTimeRange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 将已校验 QueryIntent 编译为参数化单源 SQL 或 Mongo aggregation。 */
public final class QueryPlanCompilationService {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    public QueryPlan compile(QueryIntent intent, String databaseType) {
        if (intent == null) {
            throw new IllegalArgumentException("intent is required");
        }
        QueryDialect dialect = QueryDialect.fromDatabaseType(databaseType);
        if (intent.releaseScope() != null) {
            validatePublishedDialect(intent, dialect);
        }
        validateSingleSource(intent);
        return dialect == QueryDialect.MONGODB
                ? compileMongo(intent, dialect)
                : compileSql(intent, dialect);
    }

    private void validatePublishedDialect(QueryIntent intent, QueryDialect dialect) {
        if (!dialect.supportsPublishedQuery()) {
            throw new IllegalArgumentException("当前方言不支持此类授权查询");
        }
    }

    private QueryPlan compileSql(QueryIntent intent, QueryDialect dialect) {
        List<QueryParameter> parameters = new ArrayList<>();
        List<QueryColumn> columns = new ArrayList<>();
        List<QueryLineage> lineage = new ArrayList<>();
        String table = quotePath(intent.metrics().get(0).physicalObject(), dialect);
        List<String> select = new ArrayList<>();
        List<String> groupBy = new ArrayList<>();

        boolean structured = intent.releaseScope() != null;
        boolean mysqlStructured = structured && dialect == QueryDialect.MYSQL;
        int metricIndex = 1;
        for (QueryMetric metric : intent.metrics()) {
            String field = qualifiedField(metric.physicalObject(), metric.physicalField(), dialect);
            String expression = aggregate(metric.aggregation(), field);
            String alias = "metric_" + metricIndex++;
            String projection = structured ? castAsText(expression, dialect) : expression;
            select.add(projection + " AS " + alias);
            columns.add(new QueryColumn("metric", alias, expression, metric.iri()));
            lineage.add(toLineage(intent, metric.iri(), metric.physicalObject(), metric.physicalField()));
        }
        int dimensionIndex = 1;
        for (QueryDimension dimension : intent.dimensions()) {
            String field = qualifiedField(dimension.physicalObject(), dimension.physicalField(), dialect);
            String expression = dimension.calendar() == null
                    ? dimensionExpression(field, dimension.timeGrain(), dialect)
                    : calendarDimension(field, dimension, dialect);
            String alias = "dimension_" + dimensionIndex++;
            String projection = mysqlStructured ? castAsText(expression, dialect) : expression;
            select.add(projection + " AS " + alias);
            groupBy.add(expression);
            columns.add(new QueryColumn("dimension", alias, expression, dimension.iri()));
            lineage.add(toLineage(intent, dimension.iri(), dimension.physicalObject(), dimension.physicalField()));
        }

        List<String> predicates = new ArrayList<>();
        int parameterIndex = 1;
        for (QueryFilter filter : intent.filters()) {
            String name = "p" + parameterIndex++;
            predicates.add(qualifiedField(filter.physicalObject(), filter.physicalField(), dialect) + " "
                    + operator(filter.operator()) + " :" + name);
            parameters.add(new QueryParameter(name, filter.value(), filter.textValue()));
            lineage.add(toLineage(intent, filter.iri(), filter.physicalObject(), filter.physicalField()));
        }
        QueryTimeRange timeRange = intent.timeRange();
        if (timeRange != null) {
            requireTimeMapping(timeRange);
            String timeField = qualifiedField(timeRange.physicalObject(), timeRange.physicalField(), dialect);
            if (timeRange.calendar() != null) {
                addCalendarRange(timeField, timeRange, dialect, predicates, parameters);
            } else if (timeRange.relativeDays() != null) {
                predicates.add(timeField + " >= " + relativeDateExpression(dialect, ":p_time_days"));
                parameters.add(new QueryParameter("p_time_days", timeRange.relativeDays().toString()));
            } else {
                predicates.add(timeField + " >= :p_time_from AND " + timeField + " < :p_time_to");
                parameters.add(new QueryParameter("p_time_from", timeRange.from().toString()));
                parameters.add(new QueryParameter("p_time_to", timeRange.to().plusDays(1).toString()));
            }
            lineage.add(toLineage(intent, timeRange.fieldIri(), timeRange.physicalObject(), timeRange.physicalField()));
        }

        StringBuilder statement = new StringBuilder("SELECT ")
                .append(String.join(", ", select))
                .append(" FROM ").append(table);
        if (!predicates.isEmpty()) {
            statement.append(" WHERE ").append(String.join(" AND ", predicates));
        }
        if (!groupBy.isEmpty()) {
            statement.append(" GROUP BY ").append(String.join(", ", groupBy));
            if (intent.releaseScope() != null) {
                statement.append(" ORDER BY ").append(String.join(", ", groupBy));
            }
        }
        statement.append(" LIMIT ").append(intent.limit() + (intent.releaseScope() == null ? 0 : 1));
        CompiledQuery query = new CompiledQuery("SQL", statement.toString(), parameters, columns, lineage,
                intent.dimensions(), intent.timeRange());
        return new QueryPlan(
                intent.semanticVersionId(), intent.sourceId(), dialect.databaseType(), dialect, query,
                planHash(intent, dialect, query), List.of(), structured, intent.exploratory());
    }

    private String castAsText(String expression, QueryDialect dialect) {
        return "CAST(" + expression + (dialect == QueryDialect.MYSQL ? " AS CHAR)" : " AS text)");
    }

    private QueryPlan compileMongo(QueryIntent intent, QueryDialect dialect) {
        List<QueryParameter> parameters = new ArrayList<>();
        List<QueryColumn> columns = new ArrayList<>();
        List<QueryLineage> lineage = new ArrayList<>();
        String collection = validatePath(intent.metrics().get(0).physicalObject());
        List<String> matches = new ArrayList<>();
        int parameterIndex = 1;
        for (QueryFilter filter : intent.filters()) {
            String name = "p" + parameterIndex++;
            matches.add(jsonField(filter.physicalField()) + ": {\"$" + mongoOperator(filter.operator())
                    + "\": ?" + name + "}");
            parameters.add(new QueryParameter(name, filter.value()));
            lineage.add(toLineage(intent, filter.iri(), filter.physicalObject(), filter.physicalField()));
        }
        QueryTimeRange timeRange = intent.timeRange();
        if (timeRange != null) {
            requireTimeMapping(timeRange);
            if (timeRange.relativeDays() != null) {
                matches.add(jsonField(timeRange.physicalField())
                        + ": {\"$gte\": {\"$dateSubtract\": {\"startDate\": \"$$NOW\", \"unit\": \"day\", \"amount\": ?p_time_days}}}");
                parameters.add(new QueryParameter("p_time_days", timeRange.relativeDays().toString()));
            } else {
                matches.add(jsonField(timeRange.physicalField())
                        + ": {\"$gte\": ?p_time_from, \"$lt\": ?p_time_to}");
                parameters.add(new QueryParameter("p_time_from", timeRange.from().toString()));
                parameters.add(new QueryParameter("p_time_to", timeRange.to().plusDays(1).toString()));
            }
            lineage.add(toLineage(intent, timeRange.fieldIri(), timeRange.physicalObject(), timeRange.physicalField()));
        }

        String matchStage = matches.isEmpty() ? "" : "{\"$match\": {" + String.join(", ", matches) + "}}, ";
        StringBuilder group = new StringBuilder("{\"$group\": {\"_id\": ");
        if (intent.dimensions().isEmpty()) {
            group.append("null");
        } else {
            group.append("{");
            int index = 1;
            for (QueryDimension dimension : intent.dimensions()) {
                String key = "dimension_" + index++;
                group.append(jsonString(key)).append(": ").append(jsonString("$" + validatePath(dimension.physicalField())));
                if (index <= intent.dimensions().size()) {
                    group.append(", ");
                }
                lineage.add(toLineage(intent, dimension.iri(), dimension.physicalObject(), dimension.physicalField()));
            }
            group.append("}");
        }
        int metricIndex = 1;
        for (QueryMetric metric : intent.metrics()) {
            String alias = "metric_" + metricIndex++;
            String operator = mongoAggregation(metric.aggregation());
            group.append(", ").append(jsonString(alias)).append(": {\"")
                    .append(operator).append("\": ").append(jsonString("$" + validatePath(metric.physicalField())))
                    .append("}");
            columns.add(new QueryColumn("metric", alias, "$" + operator, metric.iri()));
            lineage.add(toLineage(intent, metric.iri(), metric.physicalObject(), metric.physicalField()));
        }
        group.append("}}");
        String statement = "[" + matchStage + group + ", {\"$limit\": " + intent.limit() + "}]";
        CompiledQuery query = new CompiledQuery("MONGO_AGGREGATION", statement, parameters, columns, lineage);
        return new QueryPlan(
                intent.semanticVersionId(), intent.sourceId(), dialect.databaseType(), dialect, query,
                planHash(intent, dialect, query), List.of("MongoDB 计划需要由受控 aggregation 适配器执行"));
    }

    private void validateSingleSource(QueryIntent intent) {
        Set<String> objects = new LinkedHashSet<>();
        intent.metrics().forEach(metric -> objects.add(validatePath(metric.physicalObject())));
        intent.dimensions().forEach(dimension -> objects.add(validatePath(dimension.physicalObject())));
        intent.filters().forEach(filter -> objects.add(validatePath(filter.physicalObject())));
        if (intent.timeRange() != null) {
            requireTimeMapping(intent.timeRange());
            objects.add(validatePath(intent.timeRange().physicalObject()));
        }
        if (objects.size() != 1) {
            throw new IllegalArgumentException("query plan requires one physical object");
        }
    }

    private QueryLineage toLineage(QueryIntent intent, String iri, String object, String field) {
        return new QueryLineage(iri, intent.sourceId(), object, field);
    }

    private String aggregate(String aggregation, String field) {
        return switch (aggregation.toLowerCase()) {
            case "sum" -> "SUM(" + field + ")";
            case "count" -> "COUNT(" + field + ")";
            case "avg" -> "AVG(" + field + ")";
            case "min" -> "MIN(" + field + ")";
            case "max" -> "MAX(" + field + ")";
            default -> throw new IllegalArgumentException("unsupported aggregation: " + aggregation);
        };
    }

    private String calendarDimension(String field, QueryDimension dimension, QueryDialect dialect) {
        var calendar = dimension.calendar();
        requireCompatibleCalendar(calendar, dialect);
        if (dialect == QueryDialect.MYSQL) {
            return dimensionExpression(field, dimension.timeGrain(), dialect);
        }
        String local = calendar.instant() ? field + " AT TIME ZONE '" + calendar.timeZone() + "'"
                : "CAST(" + field + " AS timestamp without time zone)";
        return "TO_CHAR(date_trunc('" + grain(dimension.timeGrain()) + "', " + local + "), 'YYYY-MM-DD')";
    }

    private void addCalendarRange(String field, QueryTimeRange range, QueryDialect dialect, List<String> predicates,
                                  List<QueryParameter> parameters) {
        var lastExclusive = range.to().plusDays(1);
        var calendar = range.calendar();
        requireCompatibleCalendar(calendar, dialect);
        String from = range.from().toString();
        String to = lastExclusive.toString();
        if (calendar.instant()) {
            ZoneId zone = ZoneId.of(calendar.timeZone());
            from = range.from().atStartOfDay(zone).toOffsetDateTime().toString();
            to = lastExclusive.atStartOfDay(zone).toOffsetDateTime().toString();
        }
        predicates.add(field + " >= CAST(:p_time_from AS " + calendar.physicalType() + ")"
                + " AND " + field + " < CAST(:p_time_to AS " + calendar.physicalType() + ")");
        parameters.add(new QueryParameter("p_time_from", from));
        parameters.add(new QueryParameter("p_time_to", to));
    }

    private void requireCompatibleCalendar(QueryCalendar calendar, QueryDialect dialect) {
        String type = calendar.physicalType();
        if ((dialect == QueryDialect.MYSQL && !List.of("date", "datetime").contains(type))
                || (dialect != QueryDialect.MYSQL && "datetime".equals(type))) {
            throw new IllegalArgumentException("时间映射类型与来源方言不匹配");
        }
    }

    private String dimensionExpression(String field, String grain, QueryDialect dialect) {
        if (grain == null || grain.isBlank()) {
            return field;
        }
        return switch (dialect) {
            case POSTGRESQL -> "date_trunc('" + grain(grain) + "', " + field + ")";
            case MYSQL -> "week".equals(grain(grain))
                    ? "DATE_FORMAT(DATE_SUB(" + field + ", INTERVAL WEEKDAY(" + field + ") DAY), '%Y-%m-%d')"
                    : "DATE_FORMAT(" + field + ", '" + mysqlFormat(grain) + "')";
            case CLICKHOUSE -> clickhouseGrain(grain, field);
            case MONGODB -> field;
        };
    }

    private String relativeDateExpression(QueryDialect dialect, String parameter) {
        return switch (dialect) {
            case POSTGRESQL -> "CURRENT_TIMESTAMP - (" + parameter + " * INTERVAL '1 day')";
            case MYSQL -> "DATE_SUB(CURRENT_TIMESTAMP, INTERVAL " + parameter + " DAY)";
            case CLICKHOUSE -> "subtractDays(now(), " + parameter + ")";
            case MONGODB -> throw new IllegalArgumentException("MongoDB does not use SQL date expressions");
        };
    }

    private String operator(String operator) {
        return switch (operator.toLowerCase()) {
            case "eq" -> "=";
            case "neq" -> "<>";
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            case "lte" -> "<=";
            default -> throw new IllegalArgumentException("unsupported filter operator: " + operator);
        };
    }

    private String mongoOperator(String operator) {
        return switch (operator.toLowerCase()) {
            case "eq" -> "eq";
            case "neq" -> "ne";
            case "gt" -> "gt";
            case "gte" -> "gte";
            case "lt" -> "lt";
            case "lte" -> "lte";
            default -> throw new IllegalArgumentException("unsupported filter operator: " + operator);
        };
    }

    private String mongoAggregation(String aggregation) {
        return switch (aggregation.toLowerCase()) {
            case "sum" -> "$sum";
            case "count" -> "$sum";
            case "avg" -> "$avg";
            case "min" -> "$min";
            case "max" -> "$max";
            default -> throw new IllegalArgumentException("unsupported aggregation: " + aggregation);
        };
    }

    private String quotePath(String value, QueryDialect dialect) {
        String[] parts = validatePath(value).split("\\.");
        String quote = dialect == QueryDialect.MYSQL || dialect == QueryDialect.CLICKHOUSE ? "`" : "\"";
        return quote + String.join(quote + "." + quote, parts) + quote;
    }

    private String qualifiedField(String object, String field, QueryDialect dialect) {
        return quotePath(object, dialect) + "." + quotePath(field, dialect);
    }

    private String validatePath(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("physical identifier is required");
        }
        String normalized = value.trim();
        for (String part : normalized.split("\\.", -1)) {
            if (!IDENTIFIER.matcher(part).matches()) {
                throw new IllegalArgumentException("invalid physical identifier");
            }
        }
        return normalized;
    }

    private String jsonField(String value) {
        return jsonString(validatePath(value));
    }

    private String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void requireTimeMapping(QueryTimeRange range) {
        if (range.physicalObject() == null || range.physicalField() == null) {
            throw new IllegalArgumentException("time range is missing physical mapping");
        }
    }

    private String grain(String value) {
        return switch (value.toLowerCase()) {
            case "day", "week", "month", "quarter", "year" -> value.toLowerCase();
            default -> throw new IllegalArgumentException("unsupported time grain: " + value);
        };
    }

    private String mysqlFormat(String value) {
        return switch (grain(value)) {
            case "day" -> "%Y-%m-%d";
            case "month" -> "%Y-%m-01";
            case "quarter" -> "%Y-Q%q";
            case "year" -> "%Y-01-01";
            default -> throw new IllegalArgumentException("unsupported time grain: " + value);
        };
    }

    private String clickhouseGrain(String value, String field) {
        return switch (grain(value)) {
            case "day" -> "toStartOfDay(" + field + ")";
            case "week" -> "toStartOfWeek(" + field + ")";
            case "month" -> "toStartOfMonth(" + field + ")";
            case "quarter" -> "toStartOfQuarter(" + field + ")";
            case "year" -> "toStartOfYear(" + field + ")";
            default -> throw new IllegalArgumentException("unsupported time grain: " + value);
        };
    }

    private String planHash(QueryIntent intent, QueryDialect dialect, CompiledQuery query) {
        String canonical = intent.releaseScope() == null
                ? legacyHashInput(intent, dialect, query) : structuredHashInput(intent, dialect, query);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String legacyHashInput(QueryIntent intent, QueryDialect dialect, CompiledQuery query) {
        return intent.semanticVersionId() + "|" + intent.sourceId() + "|"
                + dialect.name() + "|" + query.statement() + "|"
                + query.parameters().stream().map(QueryParameter::name).sorted().toList();
    }

    private String structuredHashInput(QueryIntent intent, QueryDialect dialect, CompiledQuery query) {
        var fields = new ArrayList<>(List.of(Objects.toString(intent.semanticVersionId(), ""), intent.sourceId(),
                dialect.name(), query.statement(), intent.releaseScope()));
        if (intent.exploratory()) {
            fields.add("NL2SQL");
        }
        query.parameters().stream().sorted(Comparator.comparing(QueryParameter::name)).forEach(parameter -> {
            fields.add(parameter.name());
            fields.add(parameter.value());
            fields.add(Boolean.toString(parameter.textValue()));
        });
        return fields.stream().map(field -> field.length() + ":" + field)
                .collect(Collectors.joining());
    }
}
