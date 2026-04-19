package com.schemaplexai.service.ai;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.dao.mapper.AiModelGroupItemMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.AiModelRouteMapper;
import com.schemaplexai.model.entity.AiModel;
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

        AIModelRouter router = new AIModelRouter(
                modelFactory,
                aiModelMapper,
                aiModelRouteMapper,
                mock(AiModelGroupItemMapper.class),
                mock(ModelLoadBalancer.class)
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

        AIModelRouter router = new AIModelRouter(
                modelFactory,
                aiModelMapper,
                aiModelRouteMapper,
                mock(AiModelGroupItemMapper.class),
                mock(ModelLoadBalancer.class)
        );

        List<LangChain4jResolution> chain = router.resolveRouteChain("Claude Code Sonnet 4.6");

        assertThat(chain)
                .extracting(item -> item.config().getModelId())
                .containsExactly("claude-sonnet-4-6", "glm-4.7");
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
