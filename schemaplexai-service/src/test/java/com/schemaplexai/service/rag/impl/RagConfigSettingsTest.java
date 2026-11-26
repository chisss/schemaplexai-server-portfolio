package com.schemaplexai.service.rag.impl;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.RagConfigMapper;
import com.schemaplexai.dao.mapper.RagOperationLogMapper;
import com.schemaplexai.model.dto.system.RagConfigUpdateRequest;
import com.schemaplexai.model.entity.RagConfig;
import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import io.milvus.v2.client.MilvusClientV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * v2 配置解析：默认值、映射、脏数据收敛、跨字段校验。
 */
class RagConfigSettingsTest {

    private RagConfigMapper ragConfigMapper;
    private AiModelMapper aiModelMapper;
    private RagConfigServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ragConfigMapper = mock(RagConfigMapper.class);
        aiModelMapper = mock(AiModelMapper.class);
        ObjectProvider<MilvusClientV2> milvusProvider = mock(ObjectProvider.class);
        when(milvusProvider.getIfAvailable()).thenReturn(null);
        service = new RagConfigServiceImpl(
                ragConfigMapper,
                mock(RagOperationLogMapper.class),
                aiModelMapper,
                milvusProvider);
    }

    @Test
    @DisplayName("无配置记录时回落 v2 默认值")
    void shouldFallbackToV2Defaults() {
        when(ragConfigMapper.selectById(any())).thenReturn(null);
        when(aiModelMapper.selectOne(any())).thenReturn(null);

        RagRuntimeSettings settings = service.resolveSettings("tenant-1");

        assertThat(settings.getChunkSize()).isEqualTo(RagDefaults.CHUNK_SIZE);
        assertThat(settings.getBm25TopK()).isEqualTo(RagDefaults.BM25_TOP_K);
        assertThat(settings.getVectorTopK()).isEqualTo(RagDefaults.VECTOR_TOP_K);
        assertThat(settings.getRrfK()).isEqualTo(60);
        assertThat(settings.getFusionTopN()).isEqualTo(20);
        assertThat(settings.getRerankPolicy()).isEqualTo(RagDefaults.RERANK_POLICY_AUTO);
        assertThat(settings.getOcrMode()).isEqualTo(RagDefaults.OCR_MODE_MULTIMODAL);
        assertThat(settings.getMaxFilesPerContext()).isEqualTo(2);
        assertThat(settings.getContextTokenBudget()).isEqualTo(4000);
        assertThat(settings.isPrefilterRequired()).isTrue();
        assertThat(settings.getEvalRecallK()).isEqualTo(RagDefaults.EVAL_RECALL_K);
    }

    @Test
    @DisplayName("配置值透传到运行时设置")
    void shouldMapConfiguredValues() {
        RagConfig config = new RagConfig();
        config.setTenantId("tenant-1");
        config.setEnabled(Boolean.TRUE);
        config.setEmbeddingSource("api");
        config.setChunkSize(700);
        config.setChunkOverlap(100);
        config.setBm25Enabled(Boolean.TRUE);
        config.setBm25TopK(30);
        config.setVectorTopK(25);
        config.setRrfK(40);
        config.setFusionTopN(24);
        config.setRerankerEnabled(Boolean.TRUE);
        config.setRerankPolicy("always");
        config.setRerankerTopN(8);
        config.setMaxFilesPerContext(3);
        config.setContextTokenBudget(6000);
        config.setKeywordFastPathEnabled(Boolean.FALSE);
        config.setOcrMode("api");
        config.setOcrMaxConcurrency(4);
        config.setRetrievalCacheTtlSeconds(120);
        config.setEvalRecallK(10);
        when(ragConfigMapper.selectById(any())).thenReturn(config);
        when(aiModelMapper.selectOne(any())).thenReturn(null);

        RagRuntimeSettings settings = service.resolveSettings("tenant-1");

        assertThat(settings.getChunkSize()).isEqualTo(700);
        assertThat(settings.getBm25TopK()).isEqualTo(30);
        assertThat(settings.getVectorTopK()).isEqualTo(25);
        assertThat(settings.getRrfK()).isEqualTo(40);
        assertThat(settings.getFusionTopN()).isEqualTo(24);
        assertThat(settings.getRerankPolicy()).isEqualTo("always");
        assertThat(settings.isRerankActive()).isTrue();
        assertThat(settings.isRerankAlways()).isTrue();
        assertThat(settings.getMaxFilesPerContext()).isEqualTo(3);
        assertThat(settings.getContextTokenBudget()).isEqualTo(6000);
        assertThat(settings.isKeywordFastPathEnabled()).isFalse();
        assertThat(settings.getOcrMode()).isEqualTo("api");
        assertThat(settings.isOcrApiAllowed()).isTrue();
        assertThat(settings.getOcrMaxConcurrency()).isEqualTo(4);
        assertThat(settings.getRetrievalCacheTtlSeconds()).isEqualTo(120);
        assertThat(settings.getEvalRecallK()).isEqualTo(10);
    }

    @Test
    @DisplayName("非法 rerankPolicy 收敛到 auto，不因脏数据把精排全关")
    void shouldNormalizeIllegalRerankPolicy() {
        RagConfig config = new RagConfig();
        config.setTenantId("tenant-1");
        config.setEmbeddingSource("api");
        config.setRerankerEnabled(Boolean.TRUE);
        config.setRerankPolicy("sometimes");
        when(ragConfigMapper.selectById(any())).thenReturn(config);
        when(aiModelMapper.selectOne(any())).thenReturn(null);

        RagRuntimeSettings settings = service.resolveSettings("tenant-1");

        assertThat(settings.getRerankPolicy()).isEqualTo(RagDefaults.RERANK_POLICY_AUTO);
        assertThat(settings.isRerankAuto()).isTrue();
    }

    @Test
    @DisplayName("rerankPolicy=never 时精排整体失活")
    void shouldDisableRerankWhenPolicyNever() {
        RagConfig config = new RagConfig();
        config.setTenantId("tenant-1");
        config.setEmbeddingSource("api");
        config.setRerankerEnabled(Boolean.TRUE);
        config.setRerankPolicy("never");
        when(ragConfigMapper.selectById(any())).thenReturn(config);
        when(aiModelMapper.selectOne(any())).thenReturn(null);

        RagRuntimeSettings settings = service.resolveSettings("tenant-1");

        assertThat(settings.isRerankActive()).isFalse();
        assertThat(settings.isRerankAlways()).isFalse();
        assertThat(settings.isRerankAuto()).isFalse();
    }

    @Test
    @DisplayName("retrievalVersion 随索引/切分配置变化，用于缓存自然失效")
    void shouldChangeRetrievalVersionWhenIndexConfigChanges() {
        RagConfig config = new RagConfig();
        config.setTenantId("tenant-1");
        config.setEmbeddingSource("api");
        config.setChunkSize(600);
        config.setCollectionName("sf_context_items");
        when(ragConfigMapper.selectById(any())).thenReturn(config);
        when(aiModelMapper.selectOne(any())).thenReturn(null);

        String before = service.resolveSettings("tenant-1").retrievalVersion();
        config.setChunkSize(700);
        String after = service.resolveSettings("tenant-1").retrievalVersion();

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("分块重叠不小于分块大小时拒绝保存")
    void shouldRejectOverlapNotSmallerThanChunkSize() {
        when(ragConfigMapper.selectById(any())).thenReturn(null);
        when(aiModelMapper.selectOne(any())).thenReturn(null);
        RagConfigUpdateRequest request = new RagConfigUpdateRequest();
        request.setChunkSize(600);
        request.setChunkOverlap(600);

        assertThatThrownBy(() -> service.updateCurrentTenantConfig(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("分块重叠必须小于分块大小");
    }

    @Test
    @DisplayName("最终 TopK 大于融合候选池时拒绝保存")
    void shouldRejectTopKGreaterThanFusionPool() {
        when(ragConfigMapper.selectById(any())).thenReturn(null);
        when(aiModelMapper.selectOne(any())).thenReturn(null);
        RagConfigUpdateRequest request = new RagConfigUpdateRequest();
        request.setFusionTopN(5);
        request.setRetrievalTopK(10);

        assertThatThrownBy(() -> service.updateCurrentTenantConfig(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最终 TopK 不能大于融合候选池");
    }

    @Test
    @DisplayName("前置过滤不允许被关闭")
    void shouldRejectDisablingPrefilter() {
        when(ragConfigMapper.selectById(any())).thenReturn(null);
        when(aiModelMapper.selectOne(any())).thenReturn(null);
        RagConfigUpdateRequest request = new RagConfigUpdateRequest();
        request.setPrefilterRequired(Boolean.FALSE);

        assertThatThrownBy(() -> service.updateCurrentTenantConfig(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许关闭");
    }
}
