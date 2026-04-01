package com.schemaplexai.service.memory.rag;

import com.schemaplexai.service.vector.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * EmbeddingModel 提供者
 *
 * <p>将现有的 {@link EmbeddingService} 适配为 LangChain4J 的 {@link EmbeddingModel}，
 * 供 RAG ContentRetriever 和 EmbeddingStoreIngestor 使用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingModelProvider {

    private final EmbeddingService embeddingService;

    /**
     * 获取适配后的 EmbeddingModel
     *
     * @param tenantId 租户 ID
     * @return EmbeddingModel 实例
     */
    public EmbeddingModel getEmbeddingModel(String tenantId) {
        return new EmbeddingServiceAdapter(embeddingService, tenantId);
    }

    /**
     * 适配器：将 EmbeddingService 包装为 LangChain4J EmbeddingModel
     */
    private static class EmbeddingServiceAdapter implements EmbeddingModel {

        private final EmbeddingService embeddingService;
        private final String tenantId;

        EmbeddingServiceAdapter(EmbeddingService embeddingService, String tenantId) {
            this.embeddingService = embeddingService;
            this.tenantId = tenantId;
        }

        @Override
        public Response<Embedding> embed(String text) {
            float[] vector = embeddingService.embed(tenantId, text);
            return Response.from(Embedding.from(vector));
        }

        @Override
        public Response<Embedding> embed(TextSegment textSegment) {
            return embed(textSegment.text());
        }

        @Override
        public Response<java.util.List<Embedding>> embedAll(java.util.List<TextSegment> textSegments) {
            java.util.List<Embedding> embeddings = textSegments.stream()
                    .map(segment -> {
                        float[] vector = embeddingService.embed(tenantId, segment.text());
                        return Embedding.from(vector);
                    })
                    .toList();
            return Response.from(embeddings);
        }

        @Override
        public int dimension() {
            return embeddingService.dimension(tenantId);
        }
    }
}
