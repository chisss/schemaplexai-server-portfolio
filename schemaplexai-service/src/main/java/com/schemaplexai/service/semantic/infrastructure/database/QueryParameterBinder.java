package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.semantic.domain.model.query.QueryDialect;
import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将服务端计划参数绑定为数据库只读适配器可接受的安全字面量。 */
final class QueryParameterBinder {

    private static final Pattern PLACEHOLDER = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    String bind(String statement, List<QueryParameter> parameters) {
        return bind(statement, parameters, QueryDialect.POSTGRESQL);
    }

    String bind(String statement, List<QueryParameter> parameters, QueryDialect dialect) {
        Map<String, String> values = new HashMap<>();
        parameters.forEach(parameter -> values.put(parameter.name(), literal(parameter, dialect)));
        Matcher matcher = PLACEHOLDER.matcher(statement);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalArgumentException("query contains an unbound parameter");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String literal(QueryParameter parameter, QueryDialect dialect) {
        String value = parameter.value();
        if (parameter.textValue()) {
            return encodedText(value, dialect);
        }
        if (value != null && value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
            return value;
        }
        if (dialect == QueryDialect.MYSQL) {
            return encodedText(value == null ? "" : value, dialect);
        }
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }

    private String encodedText(String value, QueryDialect dialect) {
        String hex = HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8));
        return switch (dialect) {
            case MYSQL -> "CONVERT(X'" + hex + "' USING utf8mb4)";
            default -> "convert_from(decode('" + hex + "', 'hex'), 'UTF8')";
        };
    }
}
