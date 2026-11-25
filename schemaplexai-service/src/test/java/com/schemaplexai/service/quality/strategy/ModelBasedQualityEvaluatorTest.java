package com.schemaplexai.service.quality.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jModelFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelBasedQualityEvaluatorTest {

    // 说明：langchain4j 1.24 的 chat(ChatMessage...) 是默认方法，Mockito mock 上默认方法不执行（返回 null）；
    // 必须显式 stub 变参方法本身（any(ChatMessage[].class)），stub chat(List) 无效——默认方法不会被转发（2026-11-20 修复）

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
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenThrow(new IllegalStateException("暂不支持"));

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.score()).isEqualTo(20);
        assertThat(result.summary()).contains("模型调用失败");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().ruleCode()).isEqualTo("qa.infrastructure.model_evaluation_failed");
        assertThat(result.modelAssessment()).isEqualTo(
                new QualityEvaluationStrategy.ModelAssessment("failed", "model_evaluation_failed"));
    }

    @Test
    void shouldReturnFailureFindingWhenResponseIsNotJson() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("plain-text-response")).build());

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.hasIssue()).isTrue();
        assertThat(result.score()).isEqualTo(20);
        assertThat(result.summary()).contains("解析失败");
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().ruleCode()).isEqualTo("qa.infrastructure.model_response_invalid");
        assertThat(result.modelAssessment()).isEqualTo(
                new QualityEvaluationStrategy.ModelAssessment("failed", "model_response_invalid"));
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
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("{\"hasIssue\":false,\"score\":95,\"summary\":\"ok\",\"findings\":[]}"))
                .build());

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.modelAssessment()).isEqualTo(new QualityEvaluationStrategy.ModelAssessment("completed", null));
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
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("""
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
        assertThat(result.modelAssessment()).isEqualTo(
                new QualityEvaluationStrategy.ModelAssessment("unknown", "relaxed_response"));
    }

    @Test
    void shouldRepairStructuredFindingsWhenModelReturnsIssueSummaryOnly() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenReturn(
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
        assertThat(result.modelAssessment()).isEqualTo(
                new QualityEvaluationStrategy.ModelAssessment("completed", "structured_repair"));
    }

    @Test
    void shouldSynthesizeFindingsFromSummaryWhenRepairFails() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenReturn(
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
        assertThat(result.modelAssessment()).isEqualTo(
                new QualityEvaluationStrategy.ModelAssessment("unknown", "locally_synthesized_findings"));
    }

    @Test
    void shouldKeepRepairScoreWithoutConfirmingAChangedAssessment() {
        AiModel model = buildModel("model-1", "质量评审模型", "test");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"hasIssue":true,"score":25,"summary":"缺少验收标准","findings":[]}
                        """)).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("""
                        {"hasIssue":true,"score":50,"summary":"缺少验收标准",
                         "findings":[{"type":"omission","severity":"warning","title":"未给出验收标准"}]}
                        """)).build());

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.score()).isEqualTo(50);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.modelAssessment()).isEqualTo(
                new QualityEvaluationStrategy.ModelAssessment("unknown", "repair_changed_score"));
    }

    @Test
    void shouldKeepDefaultScoreWithoutConfirmingAnIncompleteJsonResponse() {
        AiModel model = buildModel("model-1", "质量评审模型", "test");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("{}")).build());

        QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

        assertThat(result.score()).isEqualTo(100);
        assertThat(result.findings()).isEmpty();
        assertThat(result.modelAssessment()).isEqualTo(
                new QualityEvaluationStrategy.ModelAssessment("unknown", "incomplete_response"));
    }

    @Test
    void shouldRecordDecodedAssessmentFromCompatibleTransportFallback() throws Exception {
        HttpServer callback = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String assessment = "{\"hasIssue\":false,\"score\":95,\"summary\":\"通过\",\"findings\":[]}";
        byte[] response = new ObjectMapper().writeValueAsString(
                Map.of("choices", List.of(Map.of("message", Map.of("content", assessment)))))
                .getBytes(StandardCharsets.UTF_8);
        callback.createContext("/claude/v1/chat/completions", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        callback.start();
        try {
            AiModel model = buildModel("model-1", "质量评审模型", "test");
            model.setBaseUrl("http://127.0.0.1:" + callback.getAddress().getPort() + "/claude");
            model.setDefaultParams(Map.of("protocol", "openai"));
            ChatModel chatModel = mock(ChatModel.class);
            when(aiModelMapper.selectById("model-1")).thenReturn(model);
            when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
            when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class)))
                    .thenThrow(new IllegalStateException("503"));

            QualityEvaluationStrategy.EvaluationResult result = evaluator.evaluate("model-1", "sys", "user");

            assertThat(result.score()).isEqualTo(95);
            assertThat(result.modelAssessment()).isEqualTo(
                    new QualityEvaluationStrategy.ModelAssessment("completed", "compatible_transport_fallback"));
        } finally {
            callback.stop(0);
        }
    }

    @Test
    void shouldRecoverFindingsWhenInvalidJsonContainsJsonLikeBracesInDescription() {
        AiModel model = buildModel("model-1", "Claude Code Sonnet 4.6", "claude-sonnet-4-6");
        ChatModel chatModel = mock(ChatModel.class);
        when(aiModelMapper.selectById("model-1")).thenReturn(model);
        when(modelFactory.getOrCreate(any())).thenReturn(chatModel);
        when(chatModel.chat(any(SystemMessage.class), any(UserMessage.class))).thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("""
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
