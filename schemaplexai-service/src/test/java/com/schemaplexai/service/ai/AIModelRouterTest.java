package com.schemaplexai.service.ai;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.AiModelGroupItemMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.AiModelRouteMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.AiModelGroupItem;
import com.schemaplexai.model.entity.AiModelRoute;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIModelRouterTest {

    @Test
    void shouldPrioritizeHealthyFallbackModelsWhenPrimaryConnectivityFailed() {
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        AiModelRouteMapper aiModelRouteMapper = mock(AiModelRouteMapper.class);
        LangChain4jModelFactory modelFactory = mock(LangChain4jModelFactory.class);
        ModelMetricsTracker modelMetricsTracker = mock(ModelMetricsTracker.class);
        when(modelFactory.getOrCreate(any())).thenReturn(mock(ChatModel.class));

        AiModel primary = buildModel("primary-id", "MiniMax-M2.7", "MiniMax-M2.7", "failed", 166);
        AiModel secondary = buildModel("secondary-id", "Claude Code Sonnet 4.6", "claude-sonnet-4-6", "success", 2278);
        AiModel tertiary = buildModel("tertiary-id", "Zhipu GLM 5", "glm-4.7", "success", 658);

        AiModelRoute route = new AiModelRoute();
        route.setPrimaryModelId(primary.getId());
        route.setSecondaryModelId(secondary.getId());
        route.setTertiaryModelId(tertiary.getId());
        route.setStatus(CommonConstant.STATUS_ACTIVE);

        when(aiModelMapper.selectOne(any()))
                .thenReturn(primary, primary, secondary, tertiary);
        when(aiModelRouteMapper.selectOne(any())).thenReturn(route);
        when(modelMetricsTracker.loadMetrics(primary.getId()))
                .thenReturn(new ModelMetricsTracker.RuntimeMetrics(10L, 4L, 40D, 32_000L, "critical", null));
        when(modelMetricsTracker.loadMetrics(secondary.getId()))
                .thenReturn(new ModelMetricsTracker.RuntimeMetrics(10L, 1L, 10D, 900L, "warning", null));
        when(modelMetricsTracker.loadMetrics(tertiary.getId()))
                .thenReturn(new ModelMetricsTracker.RuntimeMetrics(10L, 0L, 0D, 500L, "healthy", null));

        AIModelRouter router = new AIModelRouter(
                modelFactory,
                aiModelMapper,
                aiModelRouteMapper,
                mock(AiModelGroupItemMapper.class),
                mock(ModelLoadBalancer.class),
                modelMetricsTracker
        );

        List<LangChain4jResolution> chain = router.resolveRouteChain("MiniMax-M2.7");

        assertThat(chain)
                .extracting(item -> item.config().getModelId())
                .containsExactly("glm-4.7", "claude-sonnet-4-6", "MiniMax-M2.7");
    }

    @Test
    void shouldKeepOriginalOrderWhenPrimaryConnectivityHealthy() {
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        AiModelRouteMapper aiModelRouteMapper = mock(AiModelRouteMapper.class);
        LangChain4jModelFactory modelFactory = mock(LangChain4jModelFactory.class);
        ModelMetricsTracker modelMetricsTracker = mock(ModelMetricsTracker.class);
        when(modelFactory.getOrCreate(any())).thenReturn(mock(ChatModel.class));

        AiModel primary = buildModel("primary-id", "Claude Code Sonnet 4.6", "claude-sonnet-4-6", "success", 2278);
        AiModel secondary = buildModel("secondary-id", "Zhipu GLM 5", "glm-4.7", "success", 658);

        AiModelRoute route = new AiModelRoute();
        route.setPrimaryModelId(primary.getId());
        route.setSecondaryModelId(secondary.getId());
        route.setStatus(CommonConstant.STATUS_ACTIVE);

        when(aiModelMapper.selectOne(any()))
                .thenReturn(primary, primary, secondary);
        when(aiModelRouteMapper.selectOne(any())).thenReturn(route);
        when(modelMetricsTracker.loadMetrics(primary.getId()))
                .thenReturn(new ModelMetricsTracker.RuntimeMetrics(10L, 0L, 0D, 700L, "healthy", null));
        when(modelMetricsTracker.loadMetrics(secondary.getId()))
                .thenReturn(new ModelMetricsTracker.RuntimeMetrics(10L, 0L, 0D, 500L, "healthy", null));

        AIModelRouter router = new AIModelRouter(
                modelFactory,
                aiModelMapper,
                aiModelRouteMapper,
                mock(AiModelGroupItemMapper.class),
                mock(ModelLoadBalancer.class),
                modelMetricsTracker
        );

        List<LangChain4jResolution> chain = router.resolveRouteChain("Claude Code Sonnet 4.6");

        assertThat(chain)
                .extracting(item -> item.config().getModelId())
                .containsExactly("claude-sonnet-4-6", "glm-4.7");
    }

    @Test
    void shouldResolvePrimaryModelFromHealthyTextModels() {
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        LangChain4jModelFactory modelFactory = mock(LangChain4jModelFactory.class);

        AiModel failedFirst = buildModel("failed-id", "MiniMax-M2.5", "MiniMax-M2.5", "failed", 194);
        failedFirst.setUseCase("general_llm");
        AiModel imageModel = buildModel("image-id", "GPT Image", "gpt-image-1", "success", 300);
        imageModel.setUseCase("image_generation");
        AiModel healthyText = buildModel("healthy-id", "DeepSeek V3.2", "deepseek-v3.2", "success", 500);
        healthyText.setUseCase("general_llm");

        when(aiModelMapper.selectList(any())).thenReturn(List.of(failedFirst, imageModel, healthyText));

        AIModelRouter router = new AIModelRouter(
                modelFactory,
                aiModelMapper,
                mock(AiModelRouteMapper.class),
                mock(AiModelGroupItemMapper.class),
                mock(ModelLoadBalancer.class),
                mock(ModelMetricsTracker.class)
        );

        AiModelConfig config = router.resolvePrimaryModel("tenant-id");

        assertThat(config.getModelId()).isEqualTo("deepseek-v3.2");
    }

    @Test
    void shouldResolveGroupChainWithLoadBalancedPrimaryAndFallbackTail() {
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        AiModelGroupItemMapper groupItemMapper = mock(AiModelGroupItemMapper.class);
        LangChain4jModelFactory modelFactory = mock(LangChain4jModelFactory.class);
        ModelLoadBalancer modelLoadBalancer = mock(ModelLoadBalancer.class);
        when(modelFactory.getOrCreate(any())).thenReturn(mock(ChatModel.class));

        AiModel m1 = buildModel("m1-id", "Claude A", "claude-a", "success", 100);
        AiModel m2 = buildModel("m2-id", "Claude B", "claude-b", "success", 120);
        AiModel m3 = buildModel("m3-id", "Claude C", "claude-c", "success", 90);

        AiModelGroupItem item1 = new AiModelGroupItem();
        item1.setGroupId("group-1");
        item1.setModelId("m1-id");
        item1.setSortOrder(1);
        AiModelGroupItem item2 = new AiModelGroupItem();
        item2.setGroupId("group-1");
        item2.setModelId("m2-id");
        item2.setSortOrder(2);
        AiModelGroupItem item3 = new AiModelGroupItem();
        item3.setGroupId("group-1");
        item3.setModelId("m3-id");
        item3.setSortOrder(3);

        when(groupItemMapper.selectList(any())).thenReturn(List.of(item1, item2, item3));
        // 负载均衡命中 m2 → 链首=m2，其余按 sort_order 作降级链尾
        when(modelLoadBalancer.selectModelId("group-1", List.of("m1-id", "m2-id", "m3-id"), "tenant-1:exec-1"))
                .thenReturn("m2-id");
        // 按解析顺序返回：链首 m2 → 降级链尾 m1 → m3
        when(aiModelMapper.selectOne(any())).thenReturn(m2, m1, m3);

        AIModelRouter router = new AIModelRouter(
                modelFactory,
                aiModelMapper,
                mock(AiModelRouteMapper.class),
                groupItemMapper,
                modelLoadBalancer,
                mock(ModelMetricsTracker.class)
        );

        List<LangChain4jResolution> chain =
                router.resolveGroupChainForExecution("group-1", "tenant-1:exec-1", "medium");

        assertThat(chain)
                .extracting(item -> item.config().getModelId())
                .containsExactly("claude-b", "claude-a", "claude-c");
    }

    @Test
    void shouldSkipUnavailableMemberAndKeepChain() {
        AiModelMapper aiModelMapper = mock(AiModelMapper.class);
        AiModelGroupItemMapper groupItemMapper = mock(AiModelGroupItemMapper.class);
        LangChain4jModelFactory modelFactory = mock(LangChain4jModelFactory.class);
        ModelLoadBalancer modelLoadBalancer = mock(ModelLoadBalancer.class);
        when(modelFactory.getOrCreate(any())).thenReturn(mock(ChatModel.class));

        AiModel healthy = buildModel("m1-id", "Claude A", "claude-a", "success", 100);

        AiModelGroupItem item1 = new AiModelGroupItem();
        item1.setGroupId("group-1");
        item1.setModelId("m1-id");
        item1.setSortOrder(1);
        AiModelGroupItem item2 = new AiModelGroupItem();
        item2.setGroupId("group-1");
        item2.setModelId("m2-id");
        item2.setSortOrder(2);

        when(groupItemMapper.selectList(any())).thenReturn(List.of(item1, item2));
        when(modelLoadBalancer.selectModelId("group-1", List.of("m1-id", "m2-id"), "t:e"))
                .thenReturn("m2-id");
        // 首次解析命中成员 m2：查询返回 null（模拟不可用）→ 跳过；再解析 m1 → healthy
        when(aiModelMapper.selectOne(any())).thenReturn(null, healthy);

        AIModelRouter router = new AIModelRouter(
                modelFactory,
                aiModelMapper,
                mock(AiModelRouteMapper.class),
                groupItemMapper,
                modelLoadBalancer,
                mock(ModelMetricsTracker.class)
        );

        List<LangChain4jResolution> chain =
                router.resolveGroupChainForExecution("group-1", "t:e", "medium");

        // 命中成员不可用时跳过，健康成员成为可用链
        assertThat(chain)
                .extracting(item -> item.config().getModelId())
                .containsExactly("claude-a");
    }

    private AiModel buildModel(String id, String name, String modelId, String lastTestStatus, Integer latency) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setName(name);
        model.setModelId(modelId);
        model.setProvider("anthropic");
        model.setStatus(CommonConstant.STATUS_ACTIVE);
        model.setLastTestStatus(lastTestStatus);
        model.setLastTestLatency(latency);
        model.setTimeoutSeconds(60);
        model.setRetryCount(2);
        model.setRetryIntervalSeconds(1);
        return model;
    }
}
