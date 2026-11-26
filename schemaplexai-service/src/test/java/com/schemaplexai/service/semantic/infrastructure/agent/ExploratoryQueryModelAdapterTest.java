package com.schemaplexai.service.semantic.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort.Field;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryCandidatePort.Request;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExploratoryQueryModelAdapterTest {

    private final AIModelRouter routing = mock(AIModelRouter.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ExploratoryQueryModelAdapter adapter = new ExploratoryQueryModelAdapter(routing, new ObjectMapper());
    private final Request question = new Request("订单金额 23.40", List.of(new Field("f1", "amount", "decimal")));

    @Test
    void usesOneBoundedToolFreeTenantModelCallAndReadsExactNumbers() {
        modelOutput("""
                {"aggregates":[{"fieldId":"f1","operator":"sum"}],"dimensionIds":[],
                 "filters":[{"fieldId":"f1","value":23.40}]}
                """);
        assertThat(adapter.propose("tenant", question).filters().getFirst().value())
                .isEqualTo(new BigDecimal("23.40"));
        verify(routing).resolvePrimaryModel("tenant");

        var chat = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(chat.capture());
        assertThat(chat.getValue().messages()).hasSize(2);
        assertThat(chat.getValue().toolSpecifications()).isNullOrEmpty();
        assertThat(chat.getValue().maxOutputTokens()).isEqualTo(1_024);
        assertThat(((UserMessage) chat.getValue().messages().getLast()).singleText())
                .contains("\"question\"", "\"id\":\"f1\"", "\"name\":\"amount\"", "\"type\":\"decimal\"")
                .doesNotContain("comment", "sourceId", "physicalObject", "credential");
        var config = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(routing).buildChatModelForExecution(config.capture());
        assertThat(config.getValue().getTimeoutSeconds()).isEqualTo(20);
    }

    @Test
    void rejectsExtraDuplicateAndTrailingFields() {
        enableModel();
        var invalid = List.of(
                "{\"aggregates\":[],\"dimensionIds\":[],\"filters\":[],\"sql\":\"unexpected\"}",
                "{\"aggregates\":[{\"fieldId\":\"f1\",\"operator\":\"sum\",\"physicalField\":\"unknown\"}],"
                        + "\"dimensionIds\":[],\"filters\":[]}",
                "{\"aggregates\":[],\"dimensionIds\":[],\"dimensionIds\":[],\"filters\":[]}",
                "{\"aggregates\":[],\"dimensionIds\":[],\"filters\":[]} null");
        for (String output : invalid) {
            when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(output)).build());
            assertThatThrownBy(() -> adapter.propose("tenant", question))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("有效的探索查询候选");
        }
    }

    @Test
    void missingTenantModelFailsWithoutMakingAProviderCall() {
        assertThatThrownBy(() -> adapter.propose("tenant", question))
                .isInstanceOf(BusinessException.class).hasMessageContaining("租户没有可用的文本模型");
        verifyNoInteractions(model);
    }

    @Test
    void providerFailureReturnsNoCandidateAndDoesNotRetry() {
        enableModel();
        when(model.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("provider timeout"));
        assertThatThrownBy(() -> adapter.propose("tenant", question)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂时无法调用文本模型").hasMessageNotContaining("provider timeout");
        verify(model).chat(any(ChatRequest.class));
    }

    private void modelOutput(String output) {
        enableModel();
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(output)).build());
    }

    private void enableModel() {
        when(routing.resolvePrimaryModel("tenant"))
                .thenReturn(AiModelConfig.builder().maxTokens(2000).timeoutSeconds(300).build());
        when(routing.buildChatModelForExecution(any())).thenReturn(model);
    }
}
