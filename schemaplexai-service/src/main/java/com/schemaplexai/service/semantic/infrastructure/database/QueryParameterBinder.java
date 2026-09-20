package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将服务端计划参数绑定为数据库只读适配器可接受的安全字面量。 */
final class QueryParameterBinder {

    private static final Pattern PLACEHOLDER = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    String bind(String statement, List<QueryParameter> parameters) {
        Map<String, String> values = new HashMap<>();
        parameters.forEach(parameter -> values.put(parameter.name(), literal(parameter.value())));
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
        if (result.toString().contains(":p")) {
            throw new IllegalArgumentException("query contains an unsupported parameter placeholder");
        }
        return result.toString();
    }

    private String literal(String value) {
        if (value != null && value.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
            return value;
        }
        return "'" + (value == null ? "" : value.replace("'", "''")) + "'";
    }
}
