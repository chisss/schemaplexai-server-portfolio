package com.schemaplexai.service.vector;

/**
 * 文本嵌入服务接口
 *
 * <p>将文本转换为高维向量（浮点数组），用于 Milvus 向量检索。
 * 实现类：
 * <ul>
 *   <li>{@link impl.OpenAiEmbeddingServiceImpl} — 调用 OpenAI text-embedding-3-small</li>
 *   <li>{@link impl.MockEmbeddingServiceImpl}   — 确定性伪随机向量，用于测试/开发环境</li>
 * </ul>
 */
public interface EmbeddingService {

    /**
     * 将文本转换为嵌入向量
     *
     * @param text 输入文本
     * @return 浮点向量（维度由实现类决定，默认 1536）
     */
    float[] embed(String text);

    /**
     * 向量维度（用于 Milvus 集合创建时的 dimension 参数）
     */
    int dimension();
}
