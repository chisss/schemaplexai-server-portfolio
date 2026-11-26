package com.schemaplexai.service.rag.retrieve;

import java.util.Collection;
import java.util.List;

/**
 * 向量召回抽象。
 *
 * <p>把"怎么查向量库"从编排器里抽出来，编排器只关心"两路召回 + 融合"，
 * 这样单测可以直接塞假实现，不必起 Milvus。
 */
public interface VectorCandidateRetriever {

    boolean isAvailable(String tenantId);

    /**
     * @param tenantId   租户（前置过滤）
     * @param projectIds 项目/上下文范围（前置过滤）
     * @param query      查询文本
     * @param topK       召回数量
     */
    List<RrfFusion.VectorHit> retrieve(String tenantId, Collection<String> projectIds, String query, int topK);
}
