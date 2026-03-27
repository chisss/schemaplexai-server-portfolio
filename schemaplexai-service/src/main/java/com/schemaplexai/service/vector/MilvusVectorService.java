package com.schemaplexai.service.vector;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量检索服务
 *
 * <p>负责上下文条目的向量化存储与语义检索。
 * <p>集合名：{@code sf_context_items}，向量维度 1536，度量方式 IP（内积余弦相似度）。
 *
 * <p>降级策略：所有操作均 try-catch，Milvus 不可用时记录警告日志并返回空结果，
 * 不影响系统主流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MilvusVectorService {

    static final String COLLECTION = "sf_context_items";
    private static final String FIELD_ITEM_ID = "item_id";
    private static final String FIELD_TENANT_ID = "tenant_id";
    private static final String FIELD_AGENT_ID = "agent_id";
    private static final String FIELD_CONTEXT_ID = "context_id";
    private static final String FIELD_CONTENT_SUMMARY = "content_summary";
    private static final String FIELD_VECTOR = "vector";
    /** 相似度阈值（IP度量，越高越相似，阈值0.6 = 中等相似） */
    private static final float SIMILARITY_THRESHOLD = 0.6f;

    private final MilvusClientV2 milvusClient;
    private final EmbeddingService embeddingService;
    private final Gson gson = new Gson();

    /** 初始化集合（启动时或首次使用时调用） */
    public void ensureCollection() {
        try {
            boolean exists = milvusClient.hasCollection(
                    HasCollectionReq.builder().collectionName(COLLECTION).build());
            if (exists) return;

            // 构建 Schema
            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_ITEM_ID).dataType(DataType.VarChar)
                    .isPrimaryKey(true).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_TENANT_ID).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_AGENT_ID).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_CONTEXT_ID).dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_CONTENT_SUMMARY).dataType(DataType.VarChar).maxLength(500).build());
            schema.addField(AddFieldReq.builder()
                    .fieldName(FIELD_VECTOR).dataType(DataType.FloatVector)
                    .dimension(embeddingService.dimension()).build());

            // 创建集合
            milvusClient.createCollection(CreateCollectionReq.builder()
                    .collectionName(COLLECTION)
                    .collectionSchema(schema)
                    .build());

            // 创建 HNSW 向量索引（v2.4.x 使用 IndexParam 列表传入）
            IndexParam indexParam = IndexParam.builder()
                    .fieldName(FIELD_VECTOR)
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.IP)
                    .build();
            milvusClient.createIndex(CreateIndexReq.builder()
                    .collectionName(COLLECTION)
                    .indexParams(Collections.singletonList(indexParam))
                    .build());

            // 加载集合到内存
            milvusClient.loadCollection(LoadCollectionReq.builder().collectionName(COLLECTION).build());

            log.info("Milvus 集合 [{}] 创建并加载完成，向量维度={}", COLLECTION, embeddingService.dimension());
        } catch (Exception e) {
            log.warn("Milvus ensureCollection 异常（系统继续运行）: {}", e.getMessage());
        }
    }

    /**
     * 向量化并写入上下文条目
     *
     * @param itemId      上下文条目 ID（sf_context_item.id）
     * @param tenantId    租户 ID
     * @param agentId     关联 Agent ID（为空则填空字符串，表示全局条目）
     * @param contextId   所属上下文 ID
     * @param content     原始内容（自动截取前500字符作为 summary）
     */
    public void upsertContextItem(String itemId, String tenantId, String agentId,
                                  String contextId, String content) {
        try {
            ensureCollection();
            float[] vector = embeddingService.embed(content);
            String summary = content != null && content.length() > 500
                    ? content.substring(0, 500) : content;

            JsonObject row = new JsonObject();
            row.addProperty(FIELD_ITEM_ID, itemId);
            row.addProperty(FIELD_TENANT_ID, tenantId != null ? tenantId : "");
            row.addProperty(FIELD_AGENT_ID, agentId != null ? agentId : "");
            row.addProperty(FIELD_CONTEXT_ID, contextId != null ? contextId : "");
            row.addProperty(FIELD_CONTENT_SUMMARY, summary);
            JsonArray vectorArray = new JsonArray();
            for (float v : vector) vectorArray.add(v);
            row.add(FIELD_VECTOR, vectorArray);

            milvusClient.upsert(UpsertReq.builder()
                    .collectionName(COLLECTION)
                    .data(Collections.singletonList(row))
                    .build());

            log.debug("向量化写入 Milvus: itemId={}, tenantId={}", itemId, tenantId);
        } catch (Exception e) {
            log.warn("Milvus upsert 异常（跳过向量索引）: itemId={}, error={}", itemId, e.getMessage());
        }
    }

    /**
     * 语义检索最相关的上下文摘要列表
     *
     * @param tenantId  租户 ID（必填，隔离数据）
     * @param agentId   Agent ID（可空，为空则检索全局条目）
     * @param query     查询文本
     * @param topK      返回最多 K 条结果
     * @return 相关上下文摘要列表（已过滤低相似度结果）
     */
    public List<String> searchSimilarContext(String tenantId, String agentId, String query, int topK) {
        List<String> results = new ArrayList<>();
        try {
            if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(query)) return results;

            ensureCollection();
            float[] queryVector = embeddingService.embed(query);

            // 构建过滤条件：租户隔离 + (agent 匹配 OR 全局条目)
            String filter = String.format("tenant_id == \"%s\" && (agent_id == \"%s\" || agent_id == \"\")",
                    tenantId, agentId != null ? agentId : "");

            SearchResp resp = milvusClient.search(SearchReq.builder()
                    .collectionName(COLLECTION)
                    .data(Collections.singletonList(new FloatVec(queryVector)))
                    .filter(filter)
                    .topK(topK)
                    .outputFields(Arrays.asList(FIELD_CONTENT_SUMMARY, FIELD_ITEM_ID))
                    .build());

            if (resp == null || resp.getSearchResults() == null) return results;

            for (List<SearchResp.SearchResult> resultList : resp.getSearchResults()) {
                for (SearchResp.SearchResult result : resultList) {
                    if (result.getScore() >= SIMILARITY_THRESHOLD) {
                        Object summary = result.getEntity().get(FIELD_CONTENT_SUMMARY);
                        if (summary != null && !summary.toString().isBlank()) {
                            results.add(summary.toString());
                        }
                    }
                }
            }
            log.debug("Milvus 语义检索完成: query片段={}, found={}", query.substring(0, Math.min(50, query.length())), results.size());
        } catch (Exception e) {
            log.warn("Milvus search 异常（返回空结果）: {}", e.getMessage());
        }
        return results;
    }

    /**
     * 删除上下文条目向量
     *
     * @param itemId 上下文条目 ID
     */
    public void deleteContextItem(String itemId) {
        try {
            milvusClient.delete(DeleteReq.builder()
                    .collectionName(COLLECTION)
                    .ids(Collections.singletonList(itemId))
                    .build());
            log.debug("Milvus 向量删除: itemId={}", itemId);
        } catch (Exception e) {
            log.warn("Milvus delete 异常: itemId={}, error={}", itemId, e.getMessage());
        }
    }
}
