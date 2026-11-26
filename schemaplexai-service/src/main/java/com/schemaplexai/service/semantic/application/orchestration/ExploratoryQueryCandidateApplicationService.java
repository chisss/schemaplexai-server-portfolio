package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.semantic.application.query.ExploratoryQueryQuestionPolicy;
import com.schemaplexai.service.semantic.application.query.ExploratoryQueryQuestionPolicy.FilterEvidence;
import com.schemaplexai.service.semantic.domain.model.query.ExploratoryQuerySelection;
import com.schemaplexai.service.semantic.domain.model.query.QueryDimension;
import com.schemaplexai.service.semantic.domain.model.query.QueryFilter;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort.Field;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort.Request;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** 将受授权的固定字段投影给模型，并将候选限制在同一字段范围。 */
@Service
@RequiredArgsConstructor
public class ExploratoryQueryCandidateApplicationService {

    private static final int MAX_FIELDS = 100;
    private static final int MAX_QUESTION_LENGTH = 3_020;
    private static final int MAX_OPERATIONS_PER_ROLE = 3;
    private static final int MAX_TEXT_LENGTH = 200;
    private static final int ROW_LIMIT = 200;
    private static final Set<String> OPERATORS = Set.of("count", "sum", "avg", "min", "max");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,62}");
    private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Pattern MYSQL_UNSIGNED = Pattern.compile("(?: unsigned(?: zerofill)?| zerofill)$");

    private final ExploratoryQueryCandidatePort candidatePort;

    public ExploratoryQuerySelection propose(String tenantId, String question, List<SchemaField> allowedFields) {
        return propose(tenantId, question, allowedFields, null);
    }

    public ExploratoryQuerySelection propose(String tenantId, String question, List<SchemaField> allowedFields,
                                              String datasetName) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        if (question == null || question.isBlank() || question.length() > MAX_QUESTION_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "探索问题与补充上下文超出长度限制");
        }
        if (!ExploratoryQueryQuestionPolicy.supports(question)) {
            throw invalid("探索查询仅支持聚合、分组和等值筛选，请移除时间范围、排序或其他高级条件");
        }
        var fields = allowedColumns(allowedFields);
        List<Field> projection = fields.entrySet().stream()
                .map(entry -> new Field(entry.getKey(), entry.getValue().name(), entry.getValue().kind().label))
                .toList();
        var selection = candidatePort.propose(tenantId, new Request(question.trim(), projection, datasetName));
        if (selection == null) {
            throw invalid("模型未能用授权字段完整表达问题中的指标、时间维度或筛选条件。");
        }
        var operations = operations(selection, fields);
        for (var filter : operations.filters()) {
            if (!filterPattern(filter, false).matcher(question).find()) {
                throw invalid("模型建议的筛选值未明确出现在问题中，请补全筛选条件");
            }
        }
        var evidence = operations.filters().stream().map(this::filterEvidence).toList();
        if (ExploratoryQueryQuestionPolicy.requestsMonthGrouping(question) != (operations.month() != null)) {
            throw invalid("探索查询候选必须保留明确的月份分组");
        }
        var dimensions = new ArrayList<>(operations.dimensions().stream().map(Column::name).toList());
        if (operations.month() != null) {
            dimensions.add(operations.month().name());
        }
        if (!ExploratoryQueryQuestionPolicy.preservesConstraints(question, dimensions, evidence)) {
            throw invalid("探索查询候选未保留问题中的显式限制，请明确写出分组字段和等值筛选条件");
        }
        return selection;
    }

    public QueryIntent toIntent(String versionId, String sourceId, String physicalObject, String releaseScope,
                                List<SchemaField> allowedFields, ExploratoryQuerySelection selection) {
        String[] objectPath = physicalObject == null ? new String[0] : physicalObject.split("\\.", -1);
        if (objectPath.length != 2 || !identifier(objectPath[0]) || !identifier(objectPath[1])
                || sourceId == null || sourceId.isBlank() || releaseScope == null || releaseScope.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "探索查询未绑定授权的物理对象");
        }
        var operations = operations(selection, allowedColumns(allowedFields));
        var metrics = operations.aggregates().stream().map(aggregate -> {
            var column = aggregate.column();
            return new QueryMetric(column.iri(), column.name(), aggregate.operator(), physicalObject, column.name());
        }).toList();
        var dimensions = new ArrayList<>(operations.dimensions().stream().map(column ->
                new QueryDimension(column.iri(), column.name(), null, physicalObject, column.name())).toList());
        if (operations.month() != null) {
            var month = operations.month();
            dimensions.add(new QueryDimension(month.iri(), "月份", "month", physicalObject, month.name()));
        }
        var filters = operations.filters().stream().map(filter -> {
            var column = filter.column();
            return new QueryFilter(column.iri(), column.name(), "eq", filter.canonical(),
                    physicalObject, column.name(), column.kind() == FieldKind.TEXT);
        }).toList();
        return new QueryIntent(versionId, sourceId, metrics, dimensions, filters, null, ROW_LIMIT,
                new QueryOutput("table", "探索查询"), releaseScope, true);
    }

    private Map<String, Column> allowedColumns(List<SchemaField> fields) {
        if (fields == null || fields.size() > MAX_FIELDS || fields.stream().anyMatch(Objects::isNull)) {
            throw invalid("探索查询最多允许 100 个已授权字段");
        }
        Map<String, Column> result = new LinkedHashMap<>();
        var fieldNames = new HashSet<String>();
        for (var field : fields.stream().sorted().toList()) {
            if (!fieldNames.add(field.name())) {
                throw invalid("探索查询的 Schema 字段重复");
            }
            var type = FieldKind.from(field.dataType());
            if (type != null && identifier(field.name())) {
                String id = "f" + (result.size() + 1);
                boolean unsigned = MYSQL_UNSIGNED.matcher(field.dataType().toLowerCase(Locale.ROOT)).find();
                result.put(id, new Column(id, field.name(), type, unsigned,
                        type == FieldKind.DATE || field.dataType().toLowerCase(Locale.ROOT).matches("datetime(?:[(][0-6][)])?")));
            }
        }
        if (result.isEmpty()) {
            throw invalid("授权对象缺少可探查的字段");
        }
        return result;
    }

    private Operations operations(ExploratoryQuerySelection selection, Map<String, Column> fields) {
        if (selection == null) {
            throw invalid("请补充可用的聚合字段、维度和筛选条件");
        }
        var aggregateKeys = new HashSet<List<String>>();
        var aggregates = items(selection.aggregates(), 1).stream().map(aggregate -> {
            var column = column(fields, aggregate.fieldId());
            String operator = aggregate.operator();
            if (operator == null || !OPERATORS.contains(operator)
                    || !aggregateKeys.add(List.of(column.id(), operator))
                    || (!"count".equals(operator) && !column.kind().numeric())) {
                throw invalid("探索查询的聚合操作重复或与字段类型不匹配");
            }
            return new ResolvedAggregate(column, operator);
        }).toList();

        var dimensionIds = new HashSet<String>();
        var dimensions = items(selection.dimensionIds(), 0).stream()
                .map(id -> uniqueColumn(fields, dimensionIds, id)).toList();
        var timeGrouping = selection.timeGrouping();
        Column month = null;
        if (timeGrouping != null) {
            month = uniqueColumn(fields, dimensionIds, timeGrouping.fieldId());
            if (!"month".equals(timeGrouping.grain()) || !month.localDate()
                    || dimensionIds.size() > MAX_OPERATIONS_PER_ROLE) {
                throw invalid("按月分组需要授权的 DATE 或 DATETIME 字段，最多支持三个维度");
            }
        }
        var filterIds = new HashSet<String>();
        var filters = items(selection.filters(), 0).stream().map(filter -> {
            var column = uniqueColumn(fields, filterIds, filter.fieldId());
            return new ResolvedFilter(column, filter.value(), filterValue(column, filter.value()));
        }).toList();
        return new Operations(aggregates, dimensions, filters, month);
    }

    private <T> List<T> items(List<T> values, int min) {
        if (values == null || values.size() < min || values.size() > MAX_OPERATIONS_PER_ROLE
                || values.stream().anyMatch(Objects::isNull)) {
            throw invalid("探索查询需要 1 至 3 个聚合，最多 3 个维度和 3 个筛选");
        }
        return values;
    }

    private Column uniqueColumn(Map<String, Column> fields, Set<String> chosen, String id) {
        if (!chosen.add(id)) {
            throw invalid("探索查询的维度或筛选字段重复");
        }
        return column(fields, id);
    }

    private Column column(Map<String, Column> fields, String id) {
        Column column = fields.get(id);
        if (column == null) {
            throw invalid("探索查询引用了不在授权范围内的字段");
        }
        return column;
    }

    private String filterValue(Column column, Object value) {
        var kind = column.kind();
        if (column.unsigned() && value instanceof Number number && number.toString().startsWith("-")) {
            return failValue();
        }
        return switch (kind) {
            case INT8, INT16, INT24, INT32, INT64 -> integer(kind, column.unsigned(), value);
            case DECIMAL -> decimal(value);
            case BOOLEAN -> value instanceof Boolean flag ? flag.toString() : failValue();
            case TEXT -> value instanceof String text && !text.isBlank() && text.length() <= MAX_TEXT_LENGTH
                    && text.codePoints().noneMatch(Character::isISOControl) ? text : failValue();
            case DATE -> date(value);
            case TIMESTAMP -> failValue();
        };
    }

    private String integer(FieldKind kind, boolean unsigned, Object value) {
        BigInteger integer = exactInteger(value);
        int magnitudeBits = kind.integerBits - (unsigned ? 0 : 1);
        if (integer == null || integer.bitLength() > magnitudeBits) {
            return failValue();
        }
        return integer.toString();
    }

    private BigInteger exactInteger(Object value) {
        return switch (value) {
            case BigInteger number -> number;
            case Integer number -> BigInteger.valueOf(number.longValue());
            case Long number -> BigInteger.valueOf(number);
            case null, default -> null;
        };
    }

    private String decimal(Object value) {
        if (!(value instanceof BigDecimal number)) {
            var integer = exactInteger(value);
            return integer == null || integer.abs().toString().length() > 38 ? failValue() : integer.toString();
        }
        if (number.precision() > 38 || number.scale() < 0 || number.scale() > 18) {
            return failValue();
        }
        return number.stripTrailingZeros().toPlainString();
    }

    private String date(Object value) {
        if (!(value instanceof String text) || !DATE.matcher(text).matches()) {
            return failValue();
        }
        try {
            return LocalDate.parse(text).getYear() >= 1 ? text : failValue();
        } catch (DateTimeParseException exception) {
            return failValue();
        }
    }

    private FilterEvidence filterEvidence(ResolvedFilter filter) {
        var anywhere = filterPattern(filter, false);
        var atStart = filterPattern(filter, true);
        return new FilterEvidence(filter.column().name(), text -> anywhere.matcher(text).find(),
                text -> atStart.matcher(text).find(),
                filter.column().kind() == FieldKind.DATE ? filter.canonical() : null);
    }

    private Pattern filterPattern(ResolvedFilter filter, boolean atStart) {
        Object value = filter.original();
        var kind = filter.column().kind();
        String literal = value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
        String boundary = kind.numeric() ? "[A-Za-z0-9_.+\\-]"
                : kind == FieldKind.DATE ? "[A-Za-z0-9_\\-]" : kind == FieldKind.TEXT ? "[\\p{L}\\p{N}_]" : "[A-Za-z0-9_]";
        String prefix = atStart ? "^\\s*[\"'“‘「]?" : "(?<!" + boundary + ")";
        if (!atStart && kind == FieldKind.TEXT) {
            prefix = "(?:(?<!" + boundary + ")|(?<=为|等于|是|仅看|只看|只要|只统计|仅统计))";
        }
        String ending = kind == FieldKind.TEXT
                ? "(?=$|[\\s,，。；;、?？\\\"'”’」]|且|并且|同时|的(?:总|均|最|金|数|订|记|报))"
                : "(?!" + boundary + ")";
        return Pattern.compile(prefix + Pattern.quote(literal) + ending,
                kind == FieldKind.BOOLEAN ? Pattern.CASE_INSENSITIVE : 0);
    }

    private String failValue() {
        throw invalid("探索查询的等值筛选必须使用与字段匹配的值");
    }

    private boolean identifier(String value) {
        return IDENTIFIER.matcher(value).matches();
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.SEMANTIC_QUERY_NOT_INTERPRETABLE, message);
    }

    private enum FieldKind {
        INT8("integer", 8), INT16("integer", 16), INT24("integer", 24), INT32("integer", 32),
        INT64("integer", 64), DECIMAL("decimal", 0),
        BOOLEAN("boolean", 0), TEXT("string", 0), DATE("date", 0), TIMESTAMP("timestamp", 0);

        private static final Pattern MYSQL_COLUMN_TYPE = Pattern.compile(
                "([a-z]+)(?:\\(.*\\))?(?: unsigned)?(?: zerofill)?");

        private final String label;
        private final int integerBits;

        FieldKind(String label, int integerBits) {
            this.label = label;
            this.integerBits = integerBits;
        }

        boolean numeric() {
            return integerBits > 0 || this == DECIMAL;
        }

        static FieldKind from(String dataType) {
            String type = dataType.toLowerCase(Locale.ROOT);
            var mysqlType = MYSQL_COLUMN_TYPE.matcher(type);
            return switch (mysqlType.matches() ? mysqlType.group(1) : type) {
                case "tinyint" -> INT8;
                case "smallint", "int2" -> INT16;
                case "mediumint" -> INT24;
                case "integer", "int", "int4" -> INT32;
                case "bigint", "int8" -> INT64;
                case "numeric", "decimal", "dec", "fixed" -> DECIMAL;
                case "boolean", "bool" -> BOOLEAN;
                case "character varying", "character", "varchar", "char", "text", "bpchar",
                        "tinytext", "mediumtext", "longtext", "enum" -> TEXT;
                case "date" -> DATE;
                case "timestamp without time zone", "timestamp with time zone", "timestamp", "timestamptz",
                        "datetime" -> TIMESTAMP;
                default -> null;
            };
        }
    }

    private record Column(String id, String name, FieldKind kind, boolean unsigned, boolean localDate) {
        String iri() {
            return "urn:spx:exploration:field:" + id;
        }
    }

    private record ResolvedAggregate(Column column, String operator) {
    }

    private record ResolvedFilter(Column column, Object original, String canonical) {
    }

    private record Operations(List<ResolvedAggregate> aggregates, List<Column> dimensions, List<ResolvedFilter> filters,
                              Column month) {
    }
}
