package com.schemaplexai.service.vector.impl;

import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.vector.impl.InProcessEmbeddingServiceImpl.BuiltinEmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpenAiEmbeddingServiceImplTest {

    @Test
    void shouldRouteBuiltinEmbeddingToConfiguredOnnxModel() {
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        RagConfigService ragConfigService = mock(RagConfigService.class);
        InProcessEmbeddingServiceImpl inProcessFallback = mock(InProcessEmbeddingServiceImpl.class);

        when(ragConfigService.resolveSettings("tenant-1")).thenReturn(RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .embeddingSource("builtin")
                .builtinEmbeddingModelId("bge-small-en-v1.5")
                .embeddingDimension(384)
                .build());
        when(inProcessFallback.embed("hello rag", BuiltinEmbeddingModel.BGE_SMALL_EN))
                .thenReturn(new float[]{0.12F, 0.34F});

        OpenAiEmbeddingServiceImpl service = new OpenAiEmbeddingServiceImpl(
                aiModelMapper,
                ragConfigService,
                inProcessFallback
        );

        float[] vector = service.embed("tenant-1", "hello rag");

        assertThat(vector).containsExactly(0.12F, 0.34F);
        verify(inProcessFallback).embed("hello rag", BuiltinEmbeddingModel.BGE_SMALL_EN);
        verifyNoInteractions(aiModelMapper);
    }

    @Test
    void shouldReturnBuiltinDimensionFromConfiguredOnnxModel() {
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        RagConfigService ragConfigService = mock(RagConfigService.class);
        InProcessEmbeddingServiceImpl inProcessFallback = mock(InProcessEmbeddingServiceImpl.class);

        when(ragConfigService.resolveSettings("tenant-1")).thenReturn(RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .embeddingSource("builtin")
                .builtinEmbeddingModelId("bge-small-en-v1.5")
                .embeddingDimension(1536)
                .build());

        OpenAiEmbeddingServiceImpl service = new OpenAiEmbeddingServiceImpl(
                aiModelMapper,
                ragConfigService,
                inProcessFallback
        );

        assertThat(service.dimension("tenant-1")).isEqualTo(BuiltinEmbeddingModel.BGE_SMALL_EN.getDimension());
        verifyNoInteractions(aiModelMapper, inProcessFallback);
    }
}
