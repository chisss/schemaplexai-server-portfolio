package com.schemaplexai.service.vector.impl;

import com.schemaplexai.service.vector.EmbeddingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Mock 嵌入实现 — 使用 SHA-256 哈希生成确定性伪随机向量
 *
 * <p>激活条件: {@code ai.embedding.provider=mock}
 * <p>也可由 {@link OpenAiEmbeddingServiceImpl} 在 API Key 缺失时降级调用。
 * <p>同一文本始终生成相同向量，保证测试稳定性。
 */
@Service
@ConditionalOnProperty(prefix = "ai.embedding", name = "provider", havingValue = "mock")
public class MockEmbeddingServiceImpl implements EmbeddingService {

    private static final int DIMENSION = 1536;

    /**
     * 基于 SHA-256 哈希生成伪随机但确定性的 1536 维向量（L2归一化）
     */
    @Override
    public float[] embed(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] seed = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));

            float[] vector = new float[DIMENSION];
            // 用 seed 填充 1536 维，每 4 字节生成一个 float
            for (int i = 0; i < DIMENSION; i++) {
                int seedIdx = i % seed.length;
                // 将字节值归一化到 [-1, 1]
                vector[i] = (seed[seedIdx] & 0xFF) / 127.5f - 1.0f;
                // 引入位置相关扰动，避免所有维度值相同
                vector[i] *= (float) Math.sin((i + 1) * 0.01);
            }

            // L2 归一化
            float norm = 0f;
            for (float v : vector) norm += v * v;
            norm = (float) Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < DIMENSION; i++) vector[i] /= norm;
            }
            return vector;
        } catch (NoSuchAlgorithmException e) {
            return new float[DIMENSION]; // 全零向量兜底
        }
    }

    @Override
    public int dimension() {
        return DIMENSION;
    }
}
