package com.schemaplexai.service.semantic.infrastructure.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.constant.SystemTenantConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SystemTenantContext;
import com.schemaplexai.dao.mapper.AgentConfigMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentConfig;
import com.schemaplexai.service.ai.AIModelRouter;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.semantic.domain.port.OntologySuggestionPort;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 后端固定的本体配置 Agent，一轮模型调用只返回结构化建议。 */
@Component
@RequiredArgsConstructor
public class BuiltinOntologySuggestionAdapter implements OntologySuggestionPort {

    static final String AGENT_ID = "8915da35-d7b4-4ae0-afb0-a99be6766edb";
    static final String AGENT_CODE = "ontology_configuration";
    static final String PROMPT_CONFIG_KEY = "ontology_system_prompt";
    private static final int MAX_INPUT_LENGTH = 32_000;
    private static final int MAX_OUTPUT_LENGTH = 32_000;
    private static final int MAX_OUTPUT_TOKENS = 4096;
    private static final int MAX_TIMEOUT_SECONDS = 45;

    private final AgentMapper agentMapper;
    private final AgentConfigMapper configMapper;
    private final AIModelRouter modelRouter;
    private final ObjectMapper objectMapper;

    @Override
    public Suggestion generate(String tenantId, Request request) {
        String systemPrompt = loadPrompt();
        String input = serialize(request);
        if (input.length() > MAX_INPUT_LENGTH) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "本次本体配置和所选表结构过大，请减少选择的表后重试");
        }

        AiModelConfig tenantModel = modelRouter.resolvePrimaryModel(tenantId);
        if (tenantModel == null) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR, "当前租户没有可用的文本模型，请在模型管理中配置后重试");
        }
        AiMessage response = requestModel(tenantModel, systemPrompt, input);
        if (response == null || response.hasToolExecutionRequests()) {
            throw invalidOutput();
        }
        return readSuggestion(response.text());
    }

    private String loadPrompt() {
        Agent agent = SystemTenantContext.callAsSystem(() -> agentMapper.selectOne(new LambdaQueryWrapper<Agent>()
                .eq(Agent::getId, AGENT_ID)
                .eq(Agent::getTenantId, SystemTenantConstant.SYSTEM_TENANT_ID)
                .eq(Agent::getSystemAgentCode, AGENT_CODE)
                .eq(Agent::getIsSystemAgent, true)
                .eq(Agent::getStatus, CommonConstant.STATUS_ACTIVE)));
        if (agent == null) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR, "本体配置 Agent 尚未初始化，请联系管理员");
        }
        AgentConfig prompt = configMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getAgentId, AGENT_ID)
                .eq(AgentConfig::getConfigKey, PROMPT_CONFIG_KEY));
        if (prompt == null || prompt.getConfigValue() == null || prompt.getConfigValue().isBlank()) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR, "本体配置 Agent 缺少系统配置，请联系管理员");
        }
        return prompt.getConfigValue();
    }

    private String serialize(Request request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "无法生成本体配置建议所需的结构数据");
        }
    }

    private AiMessage requestModel(AiModelConfig tenantModel, String systemPrompt, String input) {
        int outputTokens = tenantModel.getMaxTokens() > 0
                ? Math.min(tenantModel.getMaxTokens(), MAX_OUTPUT_TOKENS) : MAX_OUTPUT_TOKENS;
        AiModelConfig bounded = tenantModel.toBuilder()
                .maxTokens(outputTokens)
                .timeoutSeconds(Math.clamp(tenantModel.getTimeoutSeconds(), 1, MAX_TIMEOUT_SECONDS))
                .build();
        ChatRequest chat = ChatRequest.builder()
                .messages(SystemMessage.from(systemPrompt), UserMessage.from(input))
                .maxOutputTokens(outputTokens)
                .build();
        try {
            return modelRouter.buildChatModelForExecution(bounded).chat(chat).aiMessage();
        } catch (RuntimeException exception) {
            throw new BusinessException(ResultCode.AGENT_CONFIG_ERROR, "本体配置 Agent 暂时无法调用文本模型，请稍后重试");
        }
    }

    private Suggestion readSuggestion(String output) {
        if (output == null || output.length() > MAX_OUTPUT_LENGTH) {
            throw invalidOutput();
        }
        Suggestion suggestion;
        try {
            suggestion = objectMapper.readerFor(Suggestion.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(output);
        } catch (JsonProcessingException exception) {
            throw invalidOutput();
        }
        if (suggestion == null || suggestion.configuration() == null || invalidText(suggestion.summary(), 1000)
                || suggestion.assumptions() == null || suggestion.assumptions().size() > 12
                || suggestion.assumptions().stream().anyMatch(text -> invalidText(text, 500))) {
            throw invalidOutput();
        }
        return suggestion;
    }

    private boolean invalidText(String value, int maxLength) {
        return value == null || value.isBlank() || value.length() > maxLength;
    }

    private BusinessException invalidOutput() {
        return new BusinessException(ResultCode.AGENT_CONFIG_ERROR, "Agent 返回的本体建议格式有误，请重新生成");
    }
}
