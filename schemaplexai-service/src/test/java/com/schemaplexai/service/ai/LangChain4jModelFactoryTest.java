package com.schemaplexai.service.ai;

import com.schemaplexai.model.entity.AiModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jModelFactoryTest {

    @Test
    void shouldUseConfiguredTimeoutWhenBuildingModel() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        AiModelConfig config = AiModelConfig.builder()
                .timeoutSeconds(180)
                .build();

        Duration timeout = factory.resolveTimeout(config);

        assertThat(timeout).isEqualTo(Duration.ofSeconds(180));
    }

    @Test
    void shouldFallbackToDefaultTimeoutWhenConfigMissing() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();

        Duration timeout = factory.resolveTimeout(AiModelConfig.builder().build());

        assertThat(timeout).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void shouldUseAnthropicMessagesProtocolForGlmProxy() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        AiModel model = buildModel("Anthropic", "glm-4.7", "https://open.bigmodel.cn/api/anthropic", null);

        AiModelConfig config = AiModelConfig.from(model);
        ChatModel chatModel = factory.getOrCreate(config);

        assertThat(config.getProtocol()).isEqualTo(AiModelConfig.PROTOCOL_ANTHROPIC);
        assertThat(config.getBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/anthropic/v1");
        assertThat(chatModel).isInstanceOf(AnthropicChatModel.class);
    }

    @Test
    void shouldUseOpenAiProtocolForClaudeProxy() {
        LangChain4jModelFactory factory = new LangChain4jModelFactory();
        AiModel model = buildModel("Anthropic", "claude-sonnet-4-6", "https://code.newcli.com/claude", null);

        AiModelConfig config = AiModelConfig.from(model);
        ChatModel chatModel = factory.getOrCreate(config);

        assertThat(config.getProtocol()).isEqualTo(AiModelConfig.PROTOCOL_OPENAI);
        assertThat(config.getBaseUrl()).isEqualTo("https://code.newcli.com/claude/v1");
        assertThat(chatModel).isInstanceOf(OpenAiChatModel.class);
        OpenAiChatRequestParameters requestParameters = ((OpenAiChatModel) chatModel).defaultRequestParameters();
        assertThat(requestParameters.maxOutputTokens()).isEqualTo(256);
        assertThat(requestParameters.maxCompletionTokens()).isNull();
    }

    @Test
    void shouldRespectExplicitProtocolOverride() {
        AiModel model = buildModel(
                "Anthropic",
                "claude-sonnet-4-6",
                "https://open.bigmodel.cn/api/anthropic",
                Map.of("protocol", "openai")
        );

        AiModelConfig config = AiModelConfig.from(model);

        assertThat(config.getProtocol()).isEqualTo(AiModelConfig.PROTOCOL_OPENAI);
        assertThat(config.getBaseUrl()).isEqualTo("https://open.bigmodel.cn/api/anthropic/v1");
    }

    private AiModel buildModel(String provider, String modelId, String baseUrl, Map<String, Object> defaultParams) {
        AiModel model = new AiModel();
        model.setProvider(provider);
        model.setModelId(modelId);
        model.setBaseUrl(baseUrl);
        model.setDefaultParams(defaultParams);
        model.setApiKeyEncrypted(Base64.getEncoder().encodeToString("test-key".getBytes(StandardCharsets.UTF_8)));
        model.setTimeoutSeconds(60);
        model.setMaxTokens(256);
        return model;
    }
}
