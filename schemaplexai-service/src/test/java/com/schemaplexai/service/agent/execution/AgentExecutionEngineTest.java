package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.AgentExecutionStatusEnum;
import com.schemaplexai.common.enums.AgentRuntimeEngineEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.service.agent.execution.AgentLoopCompletionHandler.QualityReflectionFeedback;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.LangChain4jResolution;
import com.schemaplexai.service.quality.detector.QualityDetector;
import com.schemaplexai.service.quality.orchestrator.QualityOrchestrator;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentExecutionEngineTest {

    // =========================================================================
    //  AgentModelInvoker 测试
    // =========================================================================

    @Test
    void shouldTimeoutHungModelCall() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        });
        AgentModelInvoker invoker = new AgentModelInvoker();

        assertThatThrownBy(() -> invoker.invokeWithTimeout(
                new LangChain4jResolution(chatModel, null),
                ChatRequest.builder().messages(List.of(UserMessage.from("请输出结果"))).toolSpecifications(List.of()).build(),
                50L
        ))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("AI 调用超时");
    }

    @Test
    void shouldUseConfiguredModelTimeoutWhenAvailable() {
        AgentModelInvoker invoker = new AgentModelInvoker();
        AiModelConfig config = AiModelConfig.builder().timeoutSeconds(180).build();

        long timeoutMillis = invoker.resolveTimeoutMillis(new LangChain4jResolution(null, config), 60_000L);

        assertThat(timeoutMillis).isEqualTo(180_000L);
    }

    @Test
    void shouldUseModelSpecificTimeoutDuringRetry() throws Exception {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(120L);
            return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        });
        AgentModelInvoker invoker = new AgentModelInvoker();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(50L)
                .maxModelRetries(0)
                .build();
        LangChain4jResolution resolution = new LangChain4jResolution(
                chatModel,
                AiModelConfig.builder().provider("anthropic").modelId("slow-model").timeoutSeconds(1).build()
        );

        ChatResponse response = invoker.invokeWithRetry(
                resolution,
                ChatRequest.builder().messages(List.of(UserMessage.from("请输出结果"))).toolSpecifications(List.of()).build(),
                params,
                "exec-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                mock(AgentLogService.class)
        );

        assertThat(response.aiMessage().text()).isEqualTo("ok");
    }

    @Test
    void shouldSkipTemporarilyUnavailableModelOnSubsequentChainInvocation() throws Exception {
        ChatModel unsupportedModel = mock(ChatModel.class);
        when(unsupportedModel.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("{\"error\":{\"message\":\"暂不支持\",\"type\":\"invalid_request_error\"}}"));
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AgentModelInvoker invoker = new AgentModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(200L)
                .maxModelRetries(0)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();
        AgentLogService agentLogService = mock(AgentLogService.class);
        LangChain4jResolution unavailableResolution = new LangChain4jResolution(
                unsupportedModel,
                AiModelConfig.builder().provider("anthropic").modelId("unsupported-model").timeoutSeconds(1).build()
        );
        LangChain4jResolution healthyResolution = new LangChain4jResolution(
                healthyModel,
                AiModelConfig.builder().provider("anthropic").modelId("healthy-model").timeoutSeconds(1).build()
        );

        AgentModelInvoker.ModelCallResult firstCall = invoker.invokeChainWithRetry(
                List.of(unavailableResolution, healthyResolution),
                request,
                params,
                "exec-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );
        AgentModelInvoker.ModelCallResult secondCall = invoker.invokeChainWithRetry(
                List.of(unavailableResolution, healthyResolution),
                request,
                params,
                "exec-2", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );

        assertThat(firstCall.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(secondCall.response().aiMessage().text()).isEqualTo("healthy");
        verify(unsupportedModel, times(1)).chat(any(ChatRequest.class));
        verify(healthyModel, times(2)).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    @Test
    void shouldTreatChineseTimeoutAsRetryableCooldownAndSkipOnNextRound() throws Exception {
        ChatModel slowModel = mock(ChatModel.class);
        when(slowModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return ChatResponse.builder().aiMessage(AiMessage.from("slow")).build();
        });
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AgentModelInvoker invoker = new AgentModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(50L)
                .maxModelRetries(0)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();
        AgentLogService agentLogService = mock(AgentLogService.class);
        LangChain4jResolution slowResolution = new LangChain4jResolution(
                slowModel,
                AiModelConfig.builder().provider("anthropic").modelId("slow-timeout-model").timeoutSeconds(0).build()
        );
        LangChain4jResolution healthyResolution = new LangChain4jResolution(
                healthyModel,
                AiModelConfig.builder().provider("anthropic").modelId("healthy-model").timeoutSeconds(0).build()
        );

        AgentModelInvoker.ModelCallResult firstCall = invoker.invokeChainWithRetry(
                List.of(slowResolution, healthyResolution),
                request,
                params,
                "exec-timeout-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );
        AgentModelInvoker.ModelCallResult secondCall = invoker.invokeChainWithRetry(
                List.of(slowResolution, healthyResolution),
                request,
                params,
                "exec-timeout-2", "agent-1", "tenant-1", 2, System.currentTimeMillis(),
                agentLogService
        );

        assertThat(invoker.isRetryable(new IllegalStateException("AI 调用超时，超过 1 秒"))).isTrue();
        assertThat(firstCall.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(secondCall.response().aiMessage().text()).isEqualTo("healthy");
        verify(slowModel, times(1)).chat(any(ChatRequest.class));
        verify(healthyModel, times(2)).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    @Test
    void shouldTreatProvider1302RateLimitAsRetryableCooldownAndSkipOnNextRound() throws Exception {
        ChatModel rateLimitedModel = mock(ChatModel.class);
        when(rateLimitedModel.chat(any(ChatRequest.class)))
                .thenThrow(new IllegalStateException("{\"error\":{\"code\":\"1302\",\"message\":\"您的账户已达到速率限制，请您控制请求频率\"}}"));
        ChatModel healthyModel = mock(ChatModel.class);
        when(healthyModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("healthy")).build());

        AgentModelInvoker invoker = new AgentModelInvoker();
        invoker.clearTemporaryUnavailableModels();
        AgentEngineParams params = AgentEngineParams.builder()
                .modelCallTimeoutMillis(200L)
                .maxModelRetries(0)
                .build();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(UserMessage.from("请输出结果")))
                .toolSpecifications(List.of())
                .build();
        AgentLogService agentLogService = mock(AgentLogService.class);
        LangChain4jResolution rateLimitedResolution = new LangChain4jResolution(
                rateLimitedModel,
                AiModelConfig.builder().provider("anthropic").modelId("rate-limit-model").timeoutSeconds(1).build()
        );
        LangChain4jResolution healthyResolution = new LangChain4jResolution(
                healthyModel,
                AiModelConfig.builder().provider("anthropic").modelId("healthy-model").timeoutSeconds(1).build()
        );

        AgentModelInvoker.ModelCallResult firstCall = invoker.invokeChainWithRetry(
                List.of(rateLimitedResolution, healthyResolution),
                request,
                params,
                "exec-rate-limit-1", "agent-1", "tenant-1", 1, System.currentTimeMillis(),
                agentLogService
        );
        AgentModelInvoker.ModelCallResult secondCall = invoker.invokeChainWithRetry(
                List.of(rateLimitedResolution, healthyResolution),
                request,
                params,
                "exec-rate-limit-2", "agent-1", "tenant-1", 2, System.currentTimeMillis(),
                agentLogService
        );

        assertThat(invoker.isRetryable(
                new IllegalStateException("{\"error\":{\"code\":\"1302\",\"message\":\"您的账户已达到速率限制，请您控制请求频率\"}}")
        )).isTrue();
        assertThat(firstCall.response().aiMessage().text()).isEqualTo("healthy");
        assertThat(secondCall.response().aiMessage().text()).isEqualTo("healthy");
        verify(rateLimitedModel, times(1)).chat(any(ChatRequest.class));
        verify(healthyModel, times(2)).chat(any(ChatRequest.class));
        invoker.clearTemporaryUnavailableModels();
    }

    // =========================================================================
    //  AgentLoopToolHandler 测试
    // =========================================================================

    @Test
    void shouldDisableToolsDuringQualityReflectionRound() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        List<ToolSpecification> tools = List.of(
                ToolSpecification.builder().name("sys.read").description("read file").build()
        );
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder().id("call-1").name("sys.read").arguments("{}").build()
        );

        // qualityReflectionPending=true 时 effectiveTools 为空，filterExecutable 返回空
        List<ToolExecutionRequest> filteredWhenDisabled = handler.filterExecutable(requests, List.of());
        List<ToolExecutionRequest> filteredWhenEnabled  = handler.filterExecutable(requests, tools);

        assertThat(filteredWhenDisabled).isEmpty();
        assertThat(filteredWhenEnabled).hasSize(1);
    }

    @Test
    void shouldFilterOutToolRequestsWhenNoToolsAreAvailable() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        List<ToolExecutionRequest> requests = List.of(
                ToolExecutionRequest.builder().id("call-1").name("sys.read").arguments("{}").build()
        );
        List<ToolSpecification> tools = List.of(
                ToolSpecification.builder().name("sys.read").description("read file").build()
        );

        assertThat(handler.filterExecutable(requests, List.of())).isEmpty();
        assertThat(handler.filterExecutable(requests, tools)).hasSize(1);
        assertThat(handler.filterExecutable(requests, tools).getFirst().name()).isEqualTo("sys.read");
    }

    @Test
    void shouldTruncateOversizedToolResultMessage() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("sys.grep").arguments("{}").build();

        ToolExecutionResultMessage message = handler.buildResultMessage(
                request,
                ToolResult.builder().success(true).toolCode("sys.grep").build(),
                Map.of(),
                false,
                "x".repeat(15000)
        );

        assertThat(message.text()).hasSizeLessThan(13000);
        assertThat(message.text()).contains("工具输出过长，已截断");
    }

    @Test
    void shouldIncludeRequestPathInToolResultMessage() {
        AgentLoopToolHandler handler = new AgentLoopToolHandler(new ObjectMapper());
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-2").name("sys.read")
                .arguments("{\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\",\"workdir\":\"/tmp/workspace\"}")
                .build();

        ToolExecutionResultMessage message = handler.buildResultMessage(
                request,
                ToolResult.builder().success(true).toolCode("sys.read").build(),
                Map.of(),
                false,
                "{\"output\":\"class PolicyController {}\"}"
        );

        assertThat(message.text()).contains("请求参数");
        assertThat(message.text()).contains("PolicyController.java");
        assertThat(message.text()).contains("工具结果");
    }

    // =========================================================================
    //  AgentLoopCompletionHandler 测试
    // =========================================================================

    @Test
    void shouldForceCompletionWhenEmptyResponseHasEvidence() {
        AgentLoopCompletionHandler handler = new AgentLoopCompletionHandler(null, null, null);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请输出回归摘要"),
                AiMessage.from("已收集到一部分事实"),
                ToolExecutionResultMessage.from("call-1", "sys.read", "{\"output\":\"ok\"}")
        ));

        assertThat(handler.hasCompletionEvidence(chatMemory)).isTrue();
    }

    @Test
    void shouldNotForceCompletionWhenEmptyResponseHasNoEvidence() {
        AgentLoopCompletionHandler handler = new AgentLoopCompletionHandler(null, null, null);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(UserMessage.from("请输出回归摘要")));

        assertThat(handler.hasCompletionEvidence(chatMemory)).isFalse();
    }

    @Test
    void shouldPreferToolEvidenceOverAiConclusionsWhenCollectingFallbackEvidence() {
        AgentLoopCompletionHandler handler = new AgentLoopCompletionHandler(null, null, null);
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请完成需求分析"),
                AiMessage.from("我推测 controller 应该在 com/schemaplexai 下"),
                ToolExecutionResultMessage.from(
                        "call-3", "sys.read",
                        "请求参数: {\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\"}\n工具结果: {\"output\":\"@RestController\"}"
                )
        ));
        AgentEngineParams params = AgentEngineParams.builder()
                .fallbackEvidenceLimit(5).fallbackTextLimit(200).build();

        List<String> evidences = handler.collectFallbackEvidence(chatMemory, params);

        assertThat(evidences).hasSize(1);
        assertThat(evidences.getFirst()).contains("PolicyController.java");
        assertThat(evidences.getFirst()).doesNotContain("我推测");
    }

    // =========================================================================
    //  AgentLoopQualityChecker 测试
    // =========================================================================

    @Test
    void shouldTellModelNotToCallToolsWhenQualityReflectionTriggered() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(true, "warning", "输出存在无效路径",
                        Map.of("invalidPaths", List.of("foo/bar.java"))));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();

        QualityReflectionFeedback feedback = checker.buildFeedback(null, "some content", null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.prompt()).contains("不要继续调用工具");
    }

    @Test
    void shouldGenerateQualityReflectionFeedbackWhenDetectorFindsIssue() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(null, "structural", "TODO: 待补充"))
                .thenReturn(new QualityDetector.DetectionResult(true, "warning", "结构不完整",
                        Map.of("missingSection", "验证")));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();

        QualityReflectionFeedback feedback = checker.buildFeedback(null, "TODO: 待补充", null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("结构不完整");
        assertThat(feedback.prompt()).contains("质量检测发现当前输出仍有问题");
        assertThat(feedback.prompt()).contains("missingSection");
        assertThat(feedback.prompt()).contains("验证");
    }

    @Test
    void shouldNotInvokeStructuralDetectorDuringImmediateFeedback() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();

        QualityReflectionFeedback feedback = checker.buildImmediateFeedback(null, "这是最终答案", null, 0, params);

        assertThat(feedback).isNull();
        verify(qualityOrchestrator, never()).executeDetection(any(), any(), any());
    }

    @Test
    void shouldGenerateDeliverableCompletenessFeedbackWhenOutputIsBlockedMessage() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        String output = """
                **无法完成此任务。**

                该文件内容未被成功读取，缺少必要输入，请人工补充输入后再继续。
                """;

        QualityReflectionFeedback feedback = checker.buildImmediateFeedback(null, output, null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("未形成可交付结果");
        assertThat(feedback.prompt()).contains("deliverable_completeness");
        assertThat(feedback.prompt()).contains("无法完成此任务");
        verify(qualityOrchestrator, never()).executeDetection(any(), any(), any());
    }

    @Test
    void shouldSanitizeInternalToolTracePlaceholderAndAbsolutePathFromFinalOutput() {
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(null);

        String sanitized = checker.sanitize("""
                # 文档
                - 证据来源: call_function_abcd1234_1
                - 检索方式: sys.read 和 sys.grep
                - 占位路径: .../infrastructure/repository/PolicyRepositoryImpl.java
                - 绝对路径: /Users/demo/workspace/src/main/java/com/example/PolicyController.java
                <minimax:tool_call>
                <invoke name="sys.read">
                <parameter name="path">titanium-policy-api/src/main/java/com/titanium/policy/api/PolicyChangePreviewApi.java</parameter>
                <parameter name="workdir">/Users/demo/.schemaplexai/workspaces/worktree</parameter>
                </invoke>
                </minimax:tool_call>
                """);

        assertThat(sanitized).doesNotContain("call_function_abcd1234_1");
        assertThat(sanitized).doesNotContain("sys.read");
        assertThat(sanitized).doesNotContain("sys.grep");
        assertThat(sanitized).doesNotContain(".../infrastructure/repository/PolicyRepositoryImpl.java");
        assertThat(sanitized).doesNotContain("/Users/demo/workspace/src/main/java/com/example/PolicyController.java");
        assertThat(sanitized).doesNotContain("<minimax:tool_call>");
        assertThat(sanitized).doesNotContain("<invoke");
        assertThat(sanitized).doesNotContain("/Users/demo/.schemaplexai/workspaces/worktree");
        assertThat(sanitized).contains("工具执行记录");
        assertThat(sanitized).contains("仓库检索工具");
        assertThat(sanitized).contains("仓库中未发现具体相对路径");
    }

    @Test
    void shouldGenerateGroundingFeedbackWhenMarkdownContainsMissingWorkspacePath(@TempDir Path workspaceRoot) throws Exception {
        Files.createDirectories(workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller"));
        Files.writeString(
                workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java"),
                "class PolicyController {}"
        );
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(false, "info", "ok", Map.of()));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .inputContext(Map.of("workspacePath", workspaceRoot.toString())).build();
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请输出需求分析"),
                ToolExecutionResultMessage.from("call-4", "sys.read",
                        "请求参数: {\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\"}\n工具结果: {\"output\":\"class PolicyController {}\"}")
        ));

        QualityReflectionFeedback feedback = checker.buildFeedback(ctx,
                "## 当前仓库已确认现状\n- titanium-policy-web/src/main/java/com/schemaplexai/policy/web/controller/PolicyController.java",
                chatMemory, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("不存在的文件路径");
        assertThat(feedback.prompt()).contains("com/schemaplexai");
        assertThat(feedback.prompt()).contains("invalidPaths");
    }

    @Test
    void shouldGenerateEvidenceCoverageFeedbackWhenToolHitsRealFilesButOutputDoesNotReferenceThem(@TempDir Path workspaceRoot) throws Exception {
        Files.createDirectories(workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller"));
        Files.createDirectories(workspaceRoot.resolve("titanium-policy-application/src/main/java/com/titanium/policy/application/command"));
        Files.createDirectories(workspaceRoot.resolve("liquibase"));
        Files.writeString(workspaceRoot.resolve("titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java"), "class PolicyController {}");
        Files.writeString(workspaceRoot.resolve("titanium-policy-application/src/main/java/com/titanium/policy/application/command/PolicyApplicationService.java"), "class PolicyApplicationService {}");
        Files.writeString(workspaceRoot.resolve("liquibase/20260122_init_policy_tables.sql"), "-- ddl");

        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(false, "info", "ok", Map.of()));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .inputPrompt("请输出最终 Markdown，并区分当前仓库已确认现状和建议改造/待实现项。")
                .inputContext(Map.of("workspacePath", workspaceRoot.toString())).build();
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(chatMemory.messages()).thenReturn(List.of(
                UserMessage.from("请完成需求分析"),
                ToolExecutionResultMessage.from("call-1", "sys.grep",
                        "请求参数: {\"path\":\"titanium-policy-web/src/main/java/com/titanium/policy/web/controller/PolicyController.java\"}\n工具结果: {\"output\":\"@RequestMapping(\\\"/api/policies\\\")\"}"),
                ToolExecutionResultMessage.from("call-2", "sys.read",
                        "请求参数: {\"path\":\"titanium-policy-application/src/main/java/com/titanium/policy/application/command/PolicyApplicationService.java\"}\n工具结果: {\"output\":\"class PolicyApplicationService {}\"}"),
                ToolExecutionResultMessage.from("call-3", "sys.read",
                        "请求参数: {\"path\":\"liquibase/20260122_init_policy_tables.sql\"}\n工具结果: {\"output\":\"create table t_policy (...)\"}")
        ));
        String output = """
                ## 当前仓库已确认现状
                - 已确认存在标准 Maven 模块结构，但当前尚未补充真实 controller、application 或 liquibase 文件路径。

                ## 建议改造/待实现项
                - 待确认后补充细节。
                """;

        QualityReflectionFeedback feedback = checker.buildFeedback(ctx, output, chatMemory, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("真实源码文件");
        assertThat(feedback.prompt()).contains("workspace_evidence_coverage");
        assertThat(feedback.prompt()).contains("PolicyController.java");
        assertThat(feedback.prompt()).contains("PolicyApplicationService.java");
        assertThat(feedback.prompt()).contains("20260122_init_policy_tables.sql");
    }

    @Test
    void shouldGenerateFormattingFeedbackWhenOutputContainsInternalToolTraceOrPlaceholderPath() {
        QualityOrchestrator qualityOrchestrator = mock(QualityOrchestrator.class);
        when(qualityOrchestrator.executeDetection(any(), any(), any()))
                .thenReturn(new QualityDetector.DetectionResult(false, "info", "ok", Map.of()));
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentEngineParams params = AgentEngineParams.builder().maxQualityReflections(3).build();
        String output = """
                # 需求分析
                - 证据来源: call_function_abcd1234_1
                - 检索方式: sys.read + sys.grep
                - 关键实现位于 .../infrastructure/repository/PolicyRepositoryImpl.java
                - 绝对路径示例: /Users/demo/workspace/src/main/java/com/example/PolicyController.java
                """;

        QualityReflectionFeedback feedback = checker.buildFeedback(null, output, null, 0, params);

        assertThat(feedback).isNotNull();
        assertThat(feedback.message()).contains("绝对路径");
        assertThat(feedback.prompt()).contains("artifact_formatting");
        assertThat(feedback.prompt()).contains("call_function_abcd1234_1");
        assertThat(feedback.prompt()).contains("sys.read");
        assertThat(feedback.prompt()).contains("sys.grep");
        assertThat(feedback.prompt()).contains(".../infrastructure/repository/PolicyRepositoryImpl.java");
        assertThat(feedback.prompt()).contains("/Users/demo/workspace/src/main/java/com/example/PolicyController.java");
    }

    // =========================================================================
    //  AgentExecutionEngine 测试（保留仍在 Engine 中的逻辑）
    // =========================================================================

    @Test
    void shouldPreferBlockedToolResultWhenMultipleInterruptActionsExist() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null);

        ToolResult interrupted = engine.findInterruptedToolResult(List.of(
                ToolResult.builder().toolCode("shell_command").success(false)
                        .controlAction(SecurityComplianceConstant.DECISION_PAUSE).build(),
                ToolResult.builder().toolCode("browser_run_code").success(false)
                        .controlAction(SecurityComplianceConstant.DECISION_BLOCK).build()
        ));

        assertThat(interrupted).isNotNull();
        assertThat(interrupted.getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_BLOCK);
        assertThat(interrupted.getToolCode()).isEqualTo("browser_run_code");
    }

    @Test
    void shouldPauseSoloRuntimeWhenToolRequiresHumanReview() {
        AgentLogService agentLogService = mock(AgentLogService.class);
        ExecutionEventStreamService eventStreamService = mock(ExecutionEventStreamService.class);
        AgentExecutionEngine engine = buildMinimalEngine(agentLogService, eventStreamService, null);

        AgentExecutionContext ctx = AgentExecutionContext.builder()
                .runtimeEngine(AgentRuntimeEngineEnum.SOLO_LANGCHAIN4J.getCode()).build();
        ToolResult pausedTool = ToolResult.builder()
                .toolCode("shell_command").success(false)
                .errorMessage("命中高危工具，等待人工复核")
                .controlAction(SecurityComplianceConstant.DECISION_PAUSE).build();

        AgentExecutionResult result = engine.resolveInterruptedToolResult(
                ctx, "exec-1", "agent-1", "tenant-1", 1,
                List.of(pausedTool), 10L, 20L, "conv-1", 100L);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(AgentExecutionStatusEnum.PAUSED.getCode());
        assertThat(result.getErrorMessage()).contains("等待人工复核");
        verify(agentLogService).updateExecutionStatus("exec-1", AgentExecutionStatusEnum.PAUSED.getCode(),
                "命中高危工具，等待人工复核", 10L, 20L, null);
        verify(eventStreamService).publishWithPayload(any(), any(), anyInt(), any(), any(), anyLong());
    }

    @Test
    void shouldBuildResumeUserMessageWithOptions() {
        AgentExecutionEngine engine = buildMinimalEngine(null, null, null);
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setMessage("请继续完成剩余测试");
        input.setOptions(Map.of("incidentId", "inc-1", "approved", true));

        String result = engine.buildResumeUserMessage(input);

        assertThat(result).contains("## 人工补充输入");
        assertThat(result).contains("请继续完成剩余测试");
        assertThat(result).contains("incidentId");
        assertThat(result).contains("inc-1");
        assertThat(result).contains("approved");
        assertThat(result).contains("不要重复已完成部分");
    }

    // =========================================================================
    //  辅助方法
    // =========================================================================

    private AgentExecutionEngine buildMinimalEngine(AgentLogService agentLogService,
                                                     ExecutionEventStreamService eventStreamService,
                                                     QualityOrchestrator qualityOrchestrator) {
        AgentLoopQualityChecker checker = new AgentLoopQualityChecker(qualityOrchestrator);
        AgentLoopShadowReviewService shadowReviewService = new AgentLoopShadowReviewService(checker, Runnable::run);
        AgentLoopCompletionHandler completionHandler = new AgentLoopCompletionHandler(null, checker, qualityOrchestrator);
        AgentLoopToolHandler toolHandler = new AgentLoopToolHandler(new ObjectMapper());
        AgentModelInvoker modelInvoker = new AgentModelInvoker();
        return new AgentExecutionEngine(
                agentLogService, null, null, null, new ObjectMapper(),
                eventStreamService, null, null, null,
                modelInvoker, toolHandler, completionHandler, checker, shadowReviewService);
    }
}
