package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration.Property;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyGraphRef;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaField;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObject;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaObjectType;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 将已保存的查询定义绑定到不可变快照，核对目标方言能够执行的角色和类型。 */
public final class OntologyQueryProfileCompiler {

    private static final Pattern MYSQL_TYPE = Pattern.compile("[a-z]+");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final Set<String> AGGREGATIONS = Set.of("sum", "count", "avg", "min", "max");
    private static final Set<String> GRAINS = Set.of("minute", "hour", "day", "week", "month");
    private static final Set<String> ZONE_IDS = ZoneId.getAvailableZoneIds();

    public PublishedQueryProfile compile(
            OntologyGraphRef ref, OntologyConfiguration configuration, SchemaSnapshot snapshot) {
        var profile = configuration.queryProfile();
        if (profile == null) {
            throw new IllegalArgumentException("请先配置至少一个可查询指标");
        }
        QueryDialect dialect = requireQueryDialect(snapshot);
        var owner = configuration.classes().stream()
                .filter(item -> item.id().equals(profile.classId()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("查询口径所属概念不存在"));
        SchemaObject object = snapshot.getSchema().objects().stream()
                .filter(item -> item.key().equals(owner.sourceObject()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("查询口径所属概念需要映射快照表或视图"));
        if (object.type() != SchemaObjectType.TABLE && object.type() != SchemaObjectType.VIEW) {
            throw new IllegalArgumentException("查询口径仅支持物理表或视图");
        }
        String physicalObject = identifier(object.namespace(), "Schema", dialect) + "." + identifier(object.name(), "对象", dialect);
        String zone = timeZone(profile.timeZone(), profile.time() != null);
        Map<String, Property> properties = configuration.properties().stream()
                .filter(item -> item.classId().equals(owner.id()))
                .collect(Collectors.toMap(Property::id, Function.identity()));
        Map<String, SchemaField> physical = object.fields().stream().collect(Collectors.toMap(SchemaField::name, Function.identity()));
        String prefix = "urn:spx:tenant:" + ref.tenantId() + ":semantic:" + ref.modelId() + ":property:";

        Set<List<String>> measureKeys = new HashSet<>();
        var measures = items(profile.measures(), 1, 10, "指标").stream().map(item -> {
            var field = field(prefix, properties, physical, item.propertyId(), dialect);
            String aggregation = option(item.aggregation(), AGGREGATIONS, "指标聚合");
            if (!measureKeys.add(List.of(field.id(), aggregation))) {
                throw new IllegalArgumentException("同一字段的聚合指标不能重复");
            }
            if (!"count".equals(aggregation) && !Set.of("integer", "decimal").contains(field.dataType())) {
                throw new IllegalArgumentException("求和、均值、最小值和最大值需要数值类型的属性");
            }
            return new PublishedQueryProfile.Measure(field.id(), field.iri(), field.name(), field.label(),
                    field.physicalField(), field.dataType(), field.physicalType(), aggregation);
        }).toList();

        Set<String> dimensionIds = new HashSet<>();
        var dimensions = items(profile.dimensions(), 0, 10, "维度").stream().map(item -> {
            var field = field(prefix, properties, physical, item.propertyId(), dialect);
            unique(dimensionIds, field.id(), "维度不能重复");
            return new PublishedQueryProfile.Dimension(field.id(), field.iri(), field.name(), field.label(),
                    field.physicalField(), field.dataType(), field.physicalType());
        }).toList();

        Set<String> filterIds = new HashSet<>();
        Set<String> filterLabels = new HashSet<>();
        var filters = items(profile.filters(), 0, 10, "过滤字段").stream().map(item -> {
            var field = field(prefix, properties, physical, item.propertyId(), dialect);
            unique(filterIds, field.id(), "过滤字段不能重复");
            if (!"string".equals(field.dataType())) {
                throw new IllegalArgumentException("命名过滤值需要字符类型的属性");
            }
            Set<String> filterValues = new HashSet<>();
            var values = items(item.values(), 1, 50, "过滤值").stream().map(value -> {
                String label = requiredText(value.label(), 100, "过滤值标签").trim();
                String sourceValue = requiredText(value.value(), 200, "过滤值");
                unique(filterLabels, label.toLowerCase(Locale.ROOT), "过滤值标签在本体查询中必须唯一");
                unique(filterValues, sourceValue, "同一过滤字段的物理值不能重复");
                return new PublishedQueryProfile.Value(label, sourceValue);
            }).toList();
            return new PublishedQueryProfile.Filter(field.id(), field.iri(), field.name(), field.label(),
                    field.physicalField(), field.dataType(), field.physicalType(), values);
        }).toList();

        PublishedQueryProfile.Time time = null;
        if (profile.time() != null) {
            var field = field(prefix, properties, physical, profile.time().propertyId(), dialect);
            if (!Set.of("date", "dateTime").contains(field.dataType())) {
                throw new IllegalArgumentException("时间字段需要 DATE 或受支持的时间戳");
            }
            var grains = items(profile.time().grains(), 1, GRAINS.size(), "时间粒度").stream()
                    .map(value -> option(value, GRAINS, "时间粒度")).toList();
            if (Set.copyOf(grains).size() != grains.size()) {
                throw new IllegalArgumentException("时间粒度不能重复");
            }
            if ("date".equals(field.dataType()) && grains.stream().anyMatch(Set.of("hour", "minute")::contains)) {
                throw new IllegalArgumentException("小时与分钟粒度需要时间戳字段");
            }
            time = new PublishedQueryProfile.Time(field.id(), field.iri(), field.name(), field.label(),
                    field.physicalField(), field.dataType(), field.physicalType(), grains);
        }

        return new PublishedQueryProfile(snapshot.getSourceId(), snapshot.getId(), snapshot.getFingerprint(),
                dialect.databaseType(), physicalObject, zone, measures, dimensions, filters, time);
    }

    private QueryDialect requireQueryDialect(SchemaSnapshot snapshot) {
        if (snapshot != null) {
            try {
                var dialect = QueryDialect.fromDatabaseType(snapshot.getSchema().databaseType());
                if (dialect.supportsPublishedQuery()) {
                    return dialect;
                }
            } catch (IllegalArgumentException ignored) {
                // 未识别的来源与不支持的方言使用同一发布诊断。
            }
        }
        throw new IllegalArgumentException("可查询版本需要绑定 PostgreSQL 或 MySQL Schema 快照");
    }

    private Field field(String prefix, Map<String, Property> properties, Map<String, SchemaField> physical,
                        String propertyId, QueryDialect dialect) {
        var property = properties.get(propertyId);
        if (property == null || property.sourceField() == null || !physical.containsKey(property.sourceField())) {
            throw new IllegalArgumentException("查询角色必须使用该概念绑定的快照列");
        }
        String physicalField = identifier(property.sourceField(), "列", dialect);
        String sourceType = physical.get(physicalField).dataType();
        String physicalType = dialect == QueryDialect.MYSQL ? mysqlType(sourceType) : canonicalType(sourceType);
        String logicalType = dialect == QueryDialect.MYSQL ? mysqlLogicalType(physicalType) : logicalType(physicalType);
        if (!property.dataType().equals(logicalType)) {
            throw new IllegalArgumentException("查询字段类型与源列不匹配：" + property.label());
        }
        return new Field(property.id(), prefix + property.id(), property.name(), property.label(),
                physicalField, property.dataType(), physicalType);
    }

    private String mysqlType(String value) {
        var base = MYSQL_TYPE.matcher(value.toLowerCase(Locale.ROOT));
        if (!base.lookingAt()) {
            throw new IllegalArgumentException("不支持的 MySQL 查询字段类型");
        }
        return base.group();
    }

    private String mysqlLogicalType(String physical) {
        return switch (physical) {
            case "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "enum" -> "string";
            case "tinyint", "smallint", "mediumint", "int", "integer", "bigint" -> "integer";
            case "decimal", "numeric", "dec", "fixed" -> "decimal";
            case "date" -> "date";
            case "datetime" -> "dateTime";
            default -> throw new IllegalArgumentException("不支持的 MySQL 查询字段类型：" + physical);
        };
    }

    private String canonicalType(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "timestamp" -> "timestamp without time zone";
            case "timestamptz" -> "timestamp with time zone";
            case "int2" -> "smallint";
            case "int4", "int" -> "integer";
            case "int8" -> "bigint";
            case "varchar" -> "character varying";
            case "bool" -> "boolean";
            case "decimal" -> "numeric";
            default -> value.trim().toLowerCase(Locale.ROOT);
        };
    }

    private String logicalType(String physical) {
        return switch (physical) {
            case "character varying", "character", "text" -> "string";
            case "smallint", "integer", "bigint" -> "integer";
            case "numeric" -> "decimal";
            case "boolean" -> "boolean";
            case "date" -> "date";
            case "timestamp without time zone", "timestamp with time zone" -> "dateTime";
            default -> throw new IllegalArgumentException("不支持的 PostgreSQL 查询字段类型：" + physical);
        };
    }

    private String timeZone(String value, boolean hasTime) {
        if (!hasTime && (value == null || value.isBlank())) {
            return "UTC";
        }
        if (value == null || !ZONE_IDS.contains(value)) {
            throw new IllegalArgumentException("请选择有效的 IANA 业务时区");
        }
        return value;
    }

    private <T> List<T> items(List<T> values, int min, int max, String field) {
        if (values == null && min == 0) {
            return List.of();
        }
        if (values == null || values.size() < min || values.size() > max || values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(field + "需要 " + min + " 至 " + max + " 项");
        }
        return values;
    }

    private String option(String value, Set<String> options, String field) {
        if (value == null || !options.contains(value)) {
            throw new IllegalArgumentException("不支持的" + field);
        }
        return value;
    }

    private void unique(Set<String> values, String value, String message) {
        if (!values.add(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String requiredText(String value, int limit, String field) {
        if (value == null || value.isBlank() || value.length() > limit || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + "不能为空且最多 " + limit + " 个字符");
        }
        return value;
    }

    private String identifier(String value, String field, QueryDialect dialect) {
        int maxBytes = dialect == QueryDialect.MYSQL ? 64 : 63;
        if (value == null || !IDENTIFIER.matcher(value).matches()
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException("查询" + field + "需要有效的数据库标识符");
        }
        return value;
    }

    private record Field(String id, String iri, String name, String label, String physicalField,
                         String dataType, String physicalType) {
    }
}
