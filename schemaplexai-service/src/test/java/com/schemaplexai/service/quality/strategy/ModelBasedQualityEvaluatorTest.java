package com.schemaplexai.service.quality.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelBasedQualityEvaluatorTest {

    private final AiModelMapper aiModelMapper = mock(AiModelMapper.class);
    private final LangChain4jModelFactory modelFactory = mock(LangChain4jModelFactory.class);
    private final ModelBasedQualityEvaluator evaluator =
            new ModelBasedQualityEvaluator(aiModelMapper, modelFactory, new ObjectMapper());

    @Test
    void shouldReturnFailureFindingWhenModelInvocationFails() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(), any())).thenThrow(new IllegalStateException("暂不支持"));

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.score()).isEqualTo(20);
        assertThat(result.summary()).contains("模型调用失败");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().ruleCode()).isEqualTo("qa.infrastructure.model_evaluation_failed");
    }

    @Test
    void shouldReturnFailureFindingWhenResponseIsNotJson() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(), any())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("plain-text-response")).build());

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.score()).isEqualTo(20);
        assertThat(result.summary()).contains("解析失败");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().ruleCode()).isEqualTo("qa.infrastructure.model_response_invalid");
    }

    @Test
    void shouldCapQualityReviewOutputAndRaiseTimeoutFloor() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        model.setBaseUrl("https://code.newcli.com/claude");
        model.setTimeoutSeconds(60);
        model.setMaxTokens(20_000);
        model.setDefaultParams(Map.of("protocol", "openai"));
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(), any())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"hasIssue\":false,\"score\":95,\"summary\":\"ok\",\"findings\":[]}"))
                .build());

        evaluator.evaluate("model-1", "sys", "user");

        ArgumentCaptor<AiModelConfig> configCaptor = ArgumentCaptor.forClass(AiModelConfig.class);
        verify(modelFactory).getOrCreate(configCaptor.capture());
        AiModelConfig captured = configCaptor.getValue();
        assertThat(captured.getProtocol()).isEqualTo(AiModelConfig.PROTOCOL_OPENAI);
        assertThat(captured.getMaxTokens()).isEqualTo(2048);
        assertThat(captured.getTimeoutSeconds()).isEqualTo(180);
    }

    @Test
    void shouldRecoverJsonLikeResponseWhenStringValuesContainUnescapedQuotes() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(), any())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("""
                ```json
                {
                  "hasIssue": true,
                  "score": 42,
                  "summary": "Section 3 写"发布后支持即时回滚"，但没有定义失败路径。",
                  "findings": [
                    {
                      "ruleCode": "omission.rollback",
                      "type": "omission",
                      "severity": "critical",
                      "confidence": 95,
                      "title": "回滚补偿缺失",
                      "description": "Section 3 写"发布后支持即时回滚"，但没有说明回滚失败时如何处理。",
                      "location": "Section 3",
                      "suggestion": "补充回滚与补偿流程"
                    }
                  ]
                }
                ```
                """)).build());

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.score()).isEqualTo(42);
        assertThat(result.summary()).contains("发布后支持即时回滚");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().ruleCode()).isEqualTo("omission.rollback");
        assertThat(result.findings().getFirst().description()).contains("回滚失败时如何处理");
    }

    @Test
    void shouldRepairStructuredFindingsWhenModelReturnsIssueSummaryOnly() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(), any())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("""
                        {
                          "hasIssue": true,
                          "score": 18,
                          "summary": "设计草案存在关键缺失：接口契约完全未定义、异常与失败路径缺失、幂等保护未设计。",
                          "findings": []
                        }
                        """)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("""
                        {
                          "hasIssue": true,
                          "score": 18,
                          "summary": "设计草案存在关键缺失：接口契约完全未定义、异常与失败路径缺失、幂等保护未设计。",
                          "findings": [
                            {
                              "ruleCode": "qa.dimension.omission",
                              "type": "omission",
                              "severity": "critical",
                              "confidence": 92,
                              "title": "接口契约完全未定义",
                              "description": "没有定义接口输入输出、字段约束和错误码。",
                              "location": "整体设计",
                              "suggestion": "补充完整接口契约"
                            }
                          ]
                        }
                        """)).build()
        );

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().title()).isEqualTo("接口契约完全未定义");
    }

    @Test
    void shouldSynthesizeFindingsFromSummaryWhenRepairFails() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(), any())).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("""
                        {
                          "hasIssue": true,
                          "score": 22,
                          "summary": "设计稿存在重大缺口：接口契约缺失、状态流转未定义、可观测性方案为空。",
                          "findings": []
                        }
                        """)).build()
        ).thenThrow(new IllegalStateException("repair failed"));

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.findings()).isNotEmpty();
        assertThat(result.findings()).extracting(QualityEvaluationStrategy.Finding::title)
                .anyMatch(title -> title.contains("接口契约"));
    }

    @Test
    void shouldRecoverFindingsWhenInvalidJsonContainsJsonLikeBracesInDescription() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(), any())).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("""
                ```json
                {
                  "hasIssue": true,
                  "score": 35,
                  "summary": "接口契约定义不完整。",
                  "findings": [
                    {
                      "ruleCode": "qa.dimension.omission",
                      "type": "omission",
                      "severity": "critical",
                      "confidence": 91,
                      "title": "缺少成功/失败报文",
                      "description": "未定义成功响应 {"code":0,"data":[]} 与错误数组 [400,500]。",
                      "location": "API 设计",
                      "suggestion": "补充请求响应示例"
                    }
                  ]
                }
                ```
                """)).build());

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().description()).contains("错误数组 [400,500]");
    }

    private AiModel buildModel(String id, String name, String modelId) {
        AiModel model = new AiModel();
        model.setId(id);
        model.setName(name);
        model.setProvider("Anthropic");
        model.setModelId(modelId);
        model.setApiKeyEncrypted(Base64.getEncoder().encodeToString("test-key".getBytes()));
        return model;
    }
}
