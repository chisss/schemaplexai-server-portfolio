package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.query.QueryExecutionLimits;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** 执行前的计划完整性和资源边界检查。 */
public final class QueryPlanPolicy {

    private static final Pattern PARAMETER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public void validate(QueryPlan plan, QueryExecutionLimits limits) {
        if (plan == null || limits == null) {
            throw new IllegalArgumentException("plan and limits are required");
        }
        String language = plan.query().language();
        if (!"SQL".equals(language) && !"MONGO_AGGREGATION".equals(language)) {
            throw new IllegalArgumentException("unsupported compiled query language");
        }
        if (plan.query().statement().contains(";")) {
            throw new IllegalArgumentException("compiled query must contain one statement");
        }
        Set<String> names = new HashSet<>();
        plan.query().parameters().forEach(parameter -> {
            if (!PARAMETER.matcher(parameter.name()).matches() || !names.add(parameter.name())) {
                throw new IllegalArgumentException("compiled query contains invalid or duplicate parameter");
            }
        });
        if (plan.query().statement().length() > 100_000) {
            throw new IllegalArgumentException("compiled query exceeds length limit");
        }
    }
}
