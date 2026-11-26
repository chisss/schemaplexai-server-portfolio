package com.schemaplexai.service.semantic.infrastructure.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SystemTenantContext;
import com.schemaplexai.dao.mapper.AgentConfigMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentConfig;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort.Request;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BuiltinOntologySuggestionAdapterTest {

    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentConfigMapper configs = mock(AgentConfigMapper.class);
    private final AIModelRouter routing = mock(AIModelRouter.class);
    private final ChatModel model = mock(ChatModel.class);
    private final BuiltinOntologySuggestionAdapter adapter = new BuiltinOntologySuggestionAdapter(
            agents, configs, routing, new ObjectMapper());
    private final Request request = new Request("识别工单", OntologyConfiguration.empty(), List.of());

    @Test
    void requiresFixedSeedAndNeverRoutesModelFromSystemScope() {
        assertThatThrownBy(() -> adapter.generate("tenant", request)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未初始化");
        verifyNoInteractions(routing, model);
        seedPrompt();
        when(routing.resolvePrimaryModel("tenant")).thenAnswer(invocation -> {
            assertThat(SystemTenantContext.isSystemOperation()).isFalse();
            return null;
        });
        assertThatThrownBy(() -> adapter.generate("tenant", request)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("没有可用的文本模型");
    }

    @Test
    void requestsOneBoundedToolFreeTurnAndParsesTypedProposal() {
        prepareModel("""
                {"configuration":{"classes":[],"properties":[],"relationships":[]},"summary":"候选配置","assumptions":[]}
                """);

        assertThat(adapter.generate("tenant", request).summary()).isEqualTo("候选配置");

        var chat = ArgumentCaptor.forClass(ChatRequest.class);
        verify(model).chat(chat.capture());
        assertThat(chat.getValue().messages()).hasSize(2);
        assertThat(chat.getValue().toolSpecifications()).isNullOrEmpty();
        assertThat(chat.getValue().maxOutputTokens()).isEqualTo(4096);
        var config = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(routing).buildChatModelForExecution(config.capture());
        assertThat(config.getValue().getTimeoutSeconds()).isEqualTo(45);
    }

    @Test
    void rejectsUnexpectedActionFieldInModelProposal() {
        prepareModel("""
                {"configuration":{"classes":[],"properties":[],"relationships":[]},"summary":"候选配置","assumptions":[],"publish":true}
                """);

        assertThatThrownBy(() -> adapter.generate("tenant", request)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式有误");
    }

    private void seedPrompt() {
        when(agents.selectOne(any())).thenAnswer(invocation -> {
            assertThat(SystemTenantContext.isSystemOperation()).isTrue();
            return new Agent();
        });
        var config = new AgentConfig();
        config.setConfigValue("仅生成本体配置建议");
        when(configs.selectOne(any())).thenReturn(config);
    }

    private void prepareModel(String output) {
        seedPrompt();
        when(routing.resolvePrimaryModel("tenant")).thenReturn(AiModelConfig.builder()
                .maxTokens(5000).timeoutSeconds(180).build());
        when(routing.buildChatModelForExecution(any())).thenReturn(model);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(output)).build());
    }
}
