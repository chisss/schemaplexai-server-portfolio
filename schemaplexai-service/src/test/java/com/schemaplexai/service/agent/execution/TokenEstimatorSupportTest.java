package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.ChatMessageText;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorSupportTest {

    private final TokenEstimatorSupport estimators = new TokenEstimatorSupport();

    @Test
    void shouldKeepMixedContentInTokenWindowWithThePinnedOpenAiEstimator() {
        AiModelConfig model = AiModelConfig.builder().modelId("gpt-4o").build();
        TokenWindowChatMemory memory = TokenWindowChatMemory.withMaxTokens(8192, estimators.resolveEstimator(model));
        UserMessage image = UserMessage.from(TextContent.from("描述这张图"), ImageContent.from("AQID", "image/png"));

        memory.add(image);

        assertEquals(List.of(image), memory.messages());
        assertTrue(estimators.estimateMessage(model, image) > estimators.estimateText(model, "描述这张图"));
        assertEquals("描述这张图", ChatMessageText.textOf(image));
    }

    @Test
    void shouldEstimateImagesFromDetailInsteadOfEncodedBytesForUnknownModels() {
        AiModelConfig model = AiModelConfig.builder().modelId("unrecognized").build();
        UserMessage tiny = UserMessage.from(ImageContent.from("AQID", "image/png"));
        UserMessage large = UserMessage.from(ImageContent.from("AQID".repeat(100_000), "image/png"));

        assertEquals(estimators.estimateMessage(model, tiny), estimators.estimateMessage(model, large));
        assertEquals("", ChatMessageText.textOf(large));
    }
}
