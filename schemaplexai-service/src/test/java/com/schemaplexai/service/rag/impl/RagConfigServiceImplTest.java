package com.schemaplexai.service.rag.impl;

import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.RagConfigMapper;
import com.schemaplexai.dao.mapper.RagOperationLogMapper;
import com.schemaplexai.model.entity.RagConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagConfigServiceImplTest {

    @Test
    void shouldSwitchToCompatibleManagedCollectionWhenExistingCollectionDimensionMismatches() {
        RagConfigMapper ragConfigMapper = mock(RagConfigMapper.class);
        RagOperationLogMapper ragOperationLogMapper = mock(RagOperationLogMapper.class);
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<MilvusClientV2> milvusClientProvider = mock(ObjectProvider.class);
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);

        RagConfig config = new RagConfig();
        config.setTenantId("tenant-1");
        config.setEnabled(true);
        config.setEmbeddingSource("builtin");
        config.setBuiltinEmbeddingModelId("all-MiniLM-L6-v2");
        config.setCollectionName("sf_context_items_doubao_vision_251215");
        when(ragConfigMapper.selectById("tenant-1")).thenReturn(config);
        when(milvusClientProvider.getIfAvailable()).thenReturn(milvusClient);
        when(milvusClient.hasCollection(any())).thenReturn(true);

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName("vector")
                .dataType(DataType.FloatVector)
                .dimension(2048)
                .build());
        when(milvusClient.describeCollection(any())).thenReturn(DescribeCollectionResp.builder()
                .collectionName("sf_context_items_doubao_vision_251215")
                .collectionSchema(schema)
                .build());

        RagConfigServiceImpl service = new RagConfigServiceImpl(
                ragConfigMapper,
                ragOperationLogMapper,
                aiModelMapper,
                milvusClientProvider
        );

        var settings = service.resolveSettings("tenant-1");

        assertThat(settings.getCollectionName()).isEqualTo("sf_context_items_builtin_all_minilm_l6_v2_384");
        assertThat(settings.getEmbeddingDimension()).isEqualTo(384);
    }
}
