package com.schemaplexai.service.vector.impl;

import com.schemaplexai.service.vector.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2q.AllMiniLmL6V2QuantizedEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallenv15q.BgeSmallEnV15QuantizedEmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地内嵌 Embedding 实现 — 基于 ONNX 量化模型
 *
 * <p>支持两种内置模型：
 * <ul>
 *   <li><b>all-MiniLM-L6-v2</b>（384 维）— 多语言通用，适合中英文混合场景</li>
 *   <li><b>bge-small-en-v1.5</b>（384 维）— 英文专项优化，英文语料场景推荐</li>
 * </ul>
 *
 * <p>激活条件: {@code ai.embedding.provider=in-process}
 * <p>也可由 {@link OpenAiEmbeddingServiceImpl} 在 API Key 缺失或调用失败时降级调用。
 * <p>无需外部 API，在 JVM 进程内直接运行，适合开发环境和降级兜底。
 */
@Slf4j
@Service
@Lazy
public class InProcessEmbeddingServiceImpl implements EmbeddingService {

    /** 内置 ONNX 向量模型枚举 */
    @Getter
    public enum BuiltinEmbeddingModel {
        ALL_MINI_LM("all-MiniLM-L6-v2", "多语言通用（中英文混合）", 384),
        BGE_SMALL_EN("bge-small-en-v1.5", "英文专项优化", 384);

        private final String modelId;
        private final String description;
        private final int dimension;

        BuiltinEmbeddingModel(String modelId, String description, int dimension) {
            this.modelId = modelId;
            this.description = description;
            this.dimension = dimension;
        }

        /** 根据 modelId 查找，不匹配则返回默认 ALL_MINI_LM */
        public static BuiltinEmbeddingModel fromModelId(String modelId) {
            if (modelId == null) {
                return ALL_MINI_LM;
            }
            String normalized = modelId.toLowerCase(Locale.ROOT);
            if (normalized.contains("bge-small") || normalized.contains("bge_small")) {
                return BGE_SMALL_EN;
            }
            return ALL_MINI_LM;
        }
    }

    /** 默认模型（降级兜底用），首次使用时再初始化，避免应用启动阶段加载 ONNX 失败 */
    private volatile EmbeddingModel defaultModel;
    private volatile Integer defaultDimension;

    /** 按模型类型缓存已初始化的模型实例 */
    private final Map<BuiltinEmbeddingModel, EmbeddingModel> modelCache = new ConcurrentHashMap<>();

    public InProcessEmbeddingServiceImpl() {
        log.info("本地内嵌 Embedding 模型启用懒加载: AllMiniLmL6V2Quantized (ONNX)");
    }

    @Override
    public float[] embed(String tenantId, String text) {
        Response<Embedding> response = defaultModel().embed(text == null ? "" : text);
        return response.content().vector();
    }

    /**
     * 使用指定的内置模型进行 embedding
     */
    public float[] embed(String text, BuiltinEmbeddingModel modelType) {
        EmbeddingModel model = getOrCreateModel(modelType);
        Response<Embedding> response = model.embed(text == null ? "" : text);
        return response.content().vector();
    }

    @Override
    public int dimension(String tenantId) {
        Integer dimension = defaultDimension;
        if (dimension != null) {
            return dimension;
        }
        defaultModel();
        return defaultDimension;
    }

    /**
     * 获取指定内置模型的维度
     */
    public int dimension(BuiltinEmbeddingModel modelType) {
        return modelType.getDimension();
    }

    private EmbeddingModel getOrCreateModel(BuiltinEmbeddingModel modelType) {
        if (BuiltinEmbeddingModel.ALL_MINI_LM.equals(modelType)) {
            return defaultModel();
        }
        return modelCache.computeIfAbsent(modelType, type -> {
            log.info("初始化内置 Embedding 模型: {} ({})", type.getModelId(), type.getDescription());
            return switch (type) {
                case ALL_MINI_LM -> new AllMiniLmL6V2QuantizedEmbeddingModel();
                case BGE_SMALL_EN -> new BgeSmallEnV15QuantizedEmbeddingModel();
            };
        });
    }

    private EmbeddingModel defaultModel() {
        EmbeddingModel model = defaultModel;
        if (model != null) {
            return model;
        }
        synchronized (this) {
            if (defaultModel == null) {
                log.info("初始化本地内嵌 Embedding 模型: AllMiniLmL6V2Quantized (ONNX)");
                defaultModel = new AllMiniLmL6V2QuantizedEmbeddingModel();
                defaultDimension = defaultModel.embed("dim").content().vector().length;
                modelCache.put(BuiltinEmbeddingModel.ALL_MINI_LM, defaultModel);
                log.info("本地内嵌 Embedding 模型初始化完成, dimension={}", defaultDimension);
            }
            return defaultModel;
        }
    }
}
