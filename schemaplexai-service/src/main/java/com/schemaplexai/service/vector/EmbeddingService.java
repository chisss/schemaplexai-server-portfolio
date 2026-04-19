package com.schemaplexai.service.vector;

import com.schemaplexai.common.util.SecurityUtil;

/**
 * 文本嵌入服务接口
 *
 * <p>将文本转换为高维向量（浮点数组），用于 Milvus 向量检索。
 * 实现类：
 * <ul>
 *   <li>{@link OpenAiEmbeddingServiceImpl} — 调用 OpenAI/Doubao 兼容 Embedding 接口</li>
 *   <li>{@link InProcessEmbeddingServiceImpl} — 本地 ONNX 量化模型（AllMiniLmL6V2），用于降级兜底和开发环境</li>
 * </ul>
 */
public interface EmbeddingService {

    /**
     * 将文本转换为嵌入向量
     *
     * @param tenantId 租户 ID
     * @param text 输入文本
     * @return 浮点向量
     */
    float[] embed(String tenantId, String text);

    /**
     * 向量维度（用于 Milvus 集合创建时的 dimension 参数）
     *
     * @param tenantId 租户 ID
     */
    int dimension(String tenantId);

    /**
     * 兼容旧调用：默认取当前租户
     */
    default float[] embed(String text) {
        return embed(SecurityUtil.getCurrentTenantId(), text);
    }

    /**
     * 兼容旧调用：默认取当前租户
     */
    default int dimension() {
        return dimension(SecurityUtil.getCurrentTenantId());
    }
}
