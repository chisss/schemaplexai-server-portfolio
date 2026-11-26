package com.schemaplexai.service.semantic.domain.model.query;

import java.util.List;
import java.util.Objects;

/** 固定语义版本和数据源的可审计查询计划。 */
public record QueryPlan(
        String semanticVersionId,
        String sourceId,
        String databaseType,
        QueryDialect dialect,
        CompiledQuery query,
        String planHash,
        List<String> warnings,
        boolean structured,
        boolean exploratory,
        PublishedQueryReference reference) {

    public QueryPlan(String semanticVersionId, String sourceId, String databaseType, QueryDialect dialect,
                     CompiledQuery query, String planHash, List<String> warnings, boolean structured, boolean exploratory) {
        this(semanticVersionId, sourceId, databaseType, dialect, query, planHash, warnings, structured, exploratory, null);
    }

    public QueryPlan(String semanticVersionId, String sourceId, String databaseType, QueryDialect dialect,
                     CompiledQuery query, String planHash, List<String> warnings, boolean structured) {
        this(semanticVersionId, sourceId, databaseType, dialect, query, planHash, warnings, structured, false);
    }

    public QueryPlan(String semanticVersionId, String sourceId, String databaseType, QueryDialect dialect,
                     CompiledQuery query, String planHash, List<String> warnings) {
        this(semanticVersionId, sourceId, databaseType, dialect, query, planHash, warnings, false);
    }

    public QueryPlan {
        if (!exploratory || semanticVersionId != null) {
            semanticVersionId = requireText(semanticVersionId, "semanticVersionId");
        }
        sourceId = requireText(sourceId, "sourceId");
        databaseType = requireText(databaseType, "databaseType");
        dialect = Objects.requireNonNull(dialect, "dialect is required");
        query = Objects.requireNonNull(query, "query is required");
        planHash = requireText(planHash, "planHash");
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        if (reference != null && (!structured || exploratory || dialect != QueryDialect.POSTGRESQL)) {
            throw new IllegalArgumentException("业务对账仅支持已发布的 PostgreSQL 本体计划");
        }
    }

    public QueryPlan withReference(PublishedQueryReference reference, String checksum) {
        return new QueryPlan(semanticVersionId, sourceId, databaseType, dialect, query, checksum,
                warnings, structured, exploratory, reference);
    }

    public String queryPath() {
        if (exploratory) {
            return "NL2SQL";
        }
        return structured ? "PUBLISHED_ONTOLOGY" : "LEGACY_SEMANTIC";
    }

    public String routeReason() {
        return switch (queryPath()) {
            case "NL2SQL" -> semanticVersionId == null ? "该数据集未绑定本体，已按授权生成探索计划"
                    : "未命中发布口径，已按授权生成探索计划";
            case "PUBLISHED_ONTOLOGY" -> "已命中发布口径";
            default -> "已使用历史本体口径";
        };
    }

    private static String requireText(String value, String field) {
        return Objects.requireNonNull(value, field + " is required").trim();
    }
}
