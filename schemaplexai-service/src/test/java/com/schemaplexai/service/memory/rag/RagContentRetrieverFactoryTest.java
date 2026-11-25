package com.schemaplexai.service.memory.rag;

import com.schemaplexai.service.rag.EmbeddingQuotaGuard;
import com.schemaplexai.service.rag.RagConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class RagContentRetrieverFactoryTest {

    @Test
    void shouldStartAndSkipRetrievalWhenMilvusIsDisabled() {
        EmbeddingModelProvider embeddingModelProvider = mock(EmbeddingModelProvider.class);
        RagConfigService ragConfigService = mock(RagConfigService.class);

        new ApplicationContextRunner()
                .withPropertyValues("milvus.enabled=false")
                .withUserConfiguration(RagContentRetrieverFactory.class, RagMilvusStoreFactory.class)
                .withBean(EmbeddingModelProvider.class, () -> embeddingModelProvider)
                .withBean(RagConfigService.class, () -> ragConfigService)
                .withBean(EmbeddingQuotaGuard.class, () -> mock(EmbeddingQuotaGuard.class))
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RagContentRetrieverFactory.class);
                    assertThat(context).doesNotHaveBean(RagMilvusStoreFactory.class);
                    assertThat(context.getBean(RagContentRetrieverFactory.class)
                            .createRetriever("tenant-1", 5, 0.5)).isNull();
                    verifyNoInteractions(embeddingModelProvider, ragConfigService);
                });
    }
}
