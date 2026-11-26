package com.schemaplexai.service.rag.retrieve;

import java.util.Collection;
import java.util.List;

/**
 * 检索请求。
 *
 * @param tenantId     租户（硬过滤）
 * @param projectIds   项目/上下文范围（硬过滤；空集合表示租户全量）
 * @param queryText    用户查询
 * @param topK         最终进入上下文的片段数（为空时取租户配置）
 * @param businessTag  业务标签（授信/合同/理赔等），用于 Rerank 门禁判定
 * @param forceRerank  租户/项目强制精排（高精度场景）
 */
public record RetrievalQuery(String tenantId,
                             Collection<String> projectIds,
                             String queryText,
                             Integer topK,
                             String businessTag,
                             boolean forceRerank) {

    public List<String> projectList() {
        return projectIds == null ? List.of() : List.copyOf(projectIds);
    }

    public static RetrievalQuery of(String tenantId, Collection<String> projectIds, String queryText) {
        return new RetrievalQuery(tenantId, projectIds, queryText, null, null, false);
    }
}
