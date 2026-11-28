package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.AgentLoopPromptConstant;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.langchain4j.AgentToolSessionFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import com.schemaplexai.common.enums.ToolIoTypeEnum;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Agentic Loop 工具执行处理器
 * <p>负责工具调用的过滤、限流、执行及结果序列化，从 AgentExecutionEngine 中拆分出来</p>
 */
@Component
@RequiredArgsConstructor
public class AgentLoopToolHandler {

    private final ObjectMapper objectMapper;

    private AgentExecutionEvidenceService evidenceService;

    @Autowired
    void setEvidenceService(AgentExecutionEvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @Value("${schemaplexai.agent.tools.parallel-timeout-seconds:70}")
    private long parallelTimeoutSeconds = 70;

    /**
     * 从模型返回的工具调用列表中，过滤出当前轮次允许执行的工具请求
     */
    public List<ToolExecutionRequest> filterExecutable(List<ToolExecutionRequest> requests,
                                                        List<ToolSpecification> effectiveTools) {
        if (requests == null || requests.isEmpty()) return List.of();
        Set<String> allowed = effectiveTools == null ? Set.of()
                : effectiveTools.stream()
                        .map(ToolSpecification::name)
                        .filter(StringUtils::hasText)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        if (allowed.isEmpty()) return List.of();
        return requests.stream()
                .filter(Objects::nonNull)
                .filter(r -> StringUtils.hasText(r.name()) && allowed.contains(r.name()))
                .toList();
    }

    /**
     * 将 ToolExecutionRequest 列表转换为平台 ToolCall 列表
     */
    public List<ToolCall> toToolCalls(List<ToolExecutionRequest> requests) {
        return requests.stream()
                .map(r -> ToolCall.builder()
                        .callId(r.id())
                        .toolCode(r.name())
                        .arguments(parseJsonOrEmpty(r.arguments()))
                        .build())
                .toList();
    }

    /**
     * 限制工具调用数量，超出部分截断并记录日志
     */
    public List<ToolCall> limitToolCalls(String executionId, String agentId, String tenantId,
                                          int round, List<ToolCall> toolCalls,
                                          int maxToolCallsPerRound, AgentLogService agentLogService, long startMs) {
        if (toolCalls == null || toolCalls.size() <= maxToolCallsPerRound) return toolCalls;
        agentLogService.appendLog(executionId, agentId, tenantId, "WARN", "TOOL_CALL_LIMITED", round, null,
                "本轮工具调用数 " + toolCalls.size() + " 超过上限 " + maxToolCallsPerRound
                        + "，仅执行前 " + maxToolCallsPerRound + " 个",
                null, elapsed(startMs));
        return new ArrayList<>(toolCalls.subList(0, maxToolCallsPerRound));
    }

    /**
     * 执行单个工具请求，返回执行结果及对应的 ChatMemory 消息
     */
    public ToolExecutionOutcome executeOne(AgentToolSessionFactory.RoundToolContext roundToolContext,
                                            ToolExecutionRequest request) {
        return executeOne(roundToolContext, request, ToolResultCompressionOptions.defaults());
    }

    public ToolExecutionOutcome executeOne(AgentToolSessionFactory.RoundToolContext roundToolContext,
                                           ToolExecutionRequest request,
                                           ToolResultCompressionOptions options) {
        LocalDateTime startedAt = LocalDateTime.now();
        dev.langchain4j.service.tool.ToolExecutor executor =
                roundToolContext.toolServiceContext().toolExecutors().get(request.name());
        if (executor == null) {
            ToolResult failure = buildFailureResult(request.id(), request.name(), "未找到工具执行器: " + request.name());
            return buildOutcome(request, failure, Map.of(), true, null, options, startedAt);
        }
        try {
            ToolExecutionResult result = executor.executeWithContext(request, roundToolContext.invocationContext());
            ToolResult toolResult = toPlatformResult(request, result);
            String rendered = result != null ? result.resultText() : null;
            Map<String, Object> attributes = result != null ? result.attributes() : Map.of();
            if (evidenceService != null && !roundToolContext.isDesktopTool(request.name()) && rendered != null
                    && rendered.length() > 32_000 && toolResult.isSuccess() && roundToolContext.invocationContext() != null) {
                var parameters = roundToolContext.invocationContext().invocationParameters();
                try {
                    String artifactId = evidenceService.store(parameters.get("tenantId"), parameters.get("executionId"), rendered);
                    attributes = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
                    attributes.put(AgentExecutionEvidenceService.ARTIFACT_ATTRIBUTE, artifactId);
                    rendered = "大结果已归档至 artifact_ref://" + artifactId + "（" + rendered.length()
                            + " 字符）。可用 read_agent_artifact 按需读取。";
                } catch (RuntimeException storageFailure) {
                    Metrics.counter("agent.tool.artifact.failure").increment();
                }
            }
            return buildOutcome(request, toolResult, attributes,
                    result != null ? result.isError() : !toolResult.isSuccess(), rendered, options, startedAt);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            ToolResult failure = buildFailureResult(request.id(), request.name(), "工具执行异常: " + msg);
            return buildOutcome(request, failure, Map.of(), true, null, options, startedAt);
        }
    }

    /**
     * 构建超限工具调用的占位结果
     */
    public ToolResult buildLimitResult(ToolCall toolCall, int maxToolCallsPerRound) {
        return buildFailureResult(
                toolCall != null ? toolCall.getCallId() : null,
                toolCall != null ? toolCall.getToolCode() : null,
                "本轮工具调用数量超过限制 " + maxToolCallsPerRound + "，请基于已有结果收敛并直接输出结论");
    }

    /**
     * 构建被拒绝工具调用的占位结果
     */
    public ToolResult buildRejectedResult(ToolExecutionRequest request) {
        return buildFailureResult(
                request != null ? request.id() : null,
                request != null ? request.name() : null,
                "当前请求的工具未授权或当前轮次不可用，请基于已有信息直接输出结论");
    }

    /**
     * 构建工具执行结果消息（写入 ChatMemory）
     */
    public ToolExecutionResultMessage buildResultMessage(ToolExecutionRequest request,
                                                          ToolResult toolResult,
                                                          Map<String, Object> attributes,
                                                          boolean isError,
                                                          String resultText) {
        return buildResultMessage(request, toolResult, attributes, isError, resultText, ToolResultCompressionOptions.defaults());
    }

    /**
     * 构建工具执行结果消息（写入 ChatMemory）
     */
    public ToolExecutionResultMessage buildResultMessage(ToolExecutionRequest request,
                                                         ToolResult toolResult,
                                                         Map<String, Object> attributes,
                                                         boolean isError,
                                                         String resultText,
                                                         ToolResultCompressionOptions options) {
        ToolResultCompressionOptions effectiveOptions = options != null ? options : ToolResultCompressionOptions.defaults();
        String requestSummary = summarizeRequest(request, effectiveOptions.requestSummaryLimit());
        String rendered = StringUtils.hasText(resultText) ? resultText : serializeResult(toolResult);
        StringBuilder text = new StringBuilder();
        if (StringUtils.hasText(requestSummary)) {
            text.append("请求参数: ").append(requestSummary).append("\n");
        }
        ToolResultCompressionResult compression = compressToolResult(rendered, effectiveOptions.maxMessageChars()
                - (StringUtils.hasText(requestSummary) ? requestSummary.length() + 10 : 0));
        text.append("工具结果: ").append(compression.text());
        Map<String, Object> mergedAttributes = new LinkedHashMap<>();
        if (attributes != null && !attributes.isEmpty()) {
            mergedAttributes.putAll(attributes);
        }
        mergedAttributes.put("rawChars", compression.rawChars());
        mergedAttributes.put("compressedChars", compression.compressedChars());
        mergedAttributes.put("compressionApplied", compression.compressionApplied());
        return ToolExecutionResultMessage.builder()
                .id(request.id())
                .toolName(request.name())
                .text(text.toString())
                .isError(isError)
                .attributes(mergedAttributes)
                .build();
    }

    // ---- 私有辅助方法 ----

    private ToolResult buildFailureResult(String callId, String toolCode, String errorMessage) {
        return ToolResult.builder()
                .callId(callId)
                .toolCode(toolCode)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(errorMessage)
                .build();
    }

    private ToolResult toPlatformResult(ToolExecutionRequest request, ToolExecutionResult result) {
        if (result != null && result.result() instanceof ToolResult toolResult) {
            return toolResult;
        }
        if (result == null) {
            return buildFailureResult(request.id(), request.name(), "工具执行结果为空");
        }
        JsonNode resultNode = buildPayload(result);
        return ToolResult.builder()
                .callId(request.id())
                .toolCode(request.name())
                .success(!result.isError())
                .result(resultNode)
                .errorMessage(result.isError() ? resolveError(result) : null)
                .build();
    }

    private JsonNode buildPayload(ToolExecutionResult result) {
        if (result.result() != null) return objectMapper.valueToTree(result.result());
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(result.resultText())) payload.put("content", result.resultText());
        if (result.attributes() != null && !result.attributes().isEmpty()) payload.put("attributes", result.attributes());
        return payload.isEmpty() ? objectMapper.nullNode() : objectMapper.valueToTree(payload);
    }

    private String resolveError(ToolExecutionResult result) {
        return StringUtils.hasText(result.resultText()) ? result.resultText() : "工具执行失败";
    }

    private String serializeResult(ToolResult result) {
        if (result == null) return "{}";
        try {
            if (result.isSuccess()) {
                return result.getResult() != null ? objectMapper.writeValueAsString(result.getResult()) : "{}";
            }
            String msg = result.getErrorMessage() != null ? result.getErrorMessage().replace("\"", "'") : "unknown error";
            return "{\"error\": \"" + msg + "\"}";
        } catch (Exception e) {
            return "{}";
        }
    }

    private ToolResultCompressionResult compressToolResult(String rendered, int maxResultLen) {
        int safeMaxResultLen = Math.max(256, maxResultLen);
        if (!StringUtils.hasText(rendered)) {
            return new ToolResultCompressionResult("{}", 0, 2, false);
        }
        String normalized = rendered.replace("\r\n", "\n").trim();
        if (normalized.length() <= safeMaxResultLen) {
            return new ToolResultCompressionResult(normalized, normalized.length(), normalized.length(), false);
        }

        List<String> fragments = normalized.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .limit(12)
                .toList();
        StringBuilder summary = new StringBuilder();
        for (String fragment : fragments) {
            String candidate = fragment.length() > Math.max(60, safeMaxResultLen / 3)
                    ? fragment.substring(0, Math.max(60, safeMaxResultLen / 3)) + "..."
                    : fragment;
            if (summary.length() + candidate.length() + 2 > safeMaxResultLen) {
                break;
            }
            if (!summary.isEmpty()) {
                summary.append("\n");
            }
            summary.append(candidate);
        }
        if (summary.isEmpty()) {
            summary.append(normalized, 0, Math.min(normalized.length(), safeMaxResultLen));
        }
        summary.append("\n").append(AgentLoopPromptConstant.TOOL_RESULT_TRUNCATION_NOTICE);
        return new ToolResultCompressionResult(summary.toString(), normalized.length(), summary.length(), true);
    }

    private String summarizeRequest(ToolExecutionRequest request, int requestSummaryLimit) {
        if (request == null || !StringUtils.hasText(request.arguments())) return null;
        JsonNode args = parseJsonOrEmpty(request.arguments());
        if (args == null || !args.isObject()) return null;
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String key : List.of("path", "pattern", "file", "filePath", "query", "command")) {
            JsonNode node = args.get(key);
            if (node != null && !node.isNull()) {
                if (node.isTextual() && StringUtils.hasText(node.asText())) {
                    summary.put(key, node.asText());
                } else if (node.isNumber() || node.isBoolean()) {
                    summary.put(key, node.asText());
                }
            }
        }
        if (summary.isEmpty()) return null;
        try {
            String serialized = objectMapper.writeValueAsString(summary);
            return serialized.length() > requestSummaryLimit
                    ? serialized.substring(0, requestSummaryLimit) + "..."
                    : serialized;
        } catch (Exception e) {
            String fallback = summary.toString();
            return fallback.length() > requestSummaryLimit ? fallback.substring(0, requestSummaryLimit) + "..." : fallback;
        }
    }

    private JsonNode parseJsonOrEmpty(String json) {
        if (!StringUtils.hasText(json)) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 按原顺序执行工具，只有相邻的只读调用可在有界批次内并行。 */
    public List<ToolExecutionOutcome> executeParallel(
            AgentToolSessionFactory.RoundToolContext roundCtx,
            List<ToolExecutionRequest> requests,
            ToolResultCompressionOptions compressionOptions,
            Executor parallelExecutor,
            int maxParallelTools,
            Map<String, ToolIoTypeEnum> ioTypeIndex) {
        return executeBatch(roundCtx, requests, compressionOptions, parallelExecutor,
                maxParallelTools, ioTypeIndex, () -> true);
    }

    List<ToolExecutionOutcome> executeBatch(
            AgentToolSessionFactory.RoundToolContext roundCtx,
            List<ToolExecutionRequest> requests,
            ToolResultCompressionOptions compressionOptions,
            Executor parallelExecutor,
            int maxParallelTools,
            Map<String, ToolIoTypeEnum> ioTypeIndex,
            BooleanSupplier canDispatch) {
        return executeBatch(requests, compressionOptions, parallelExecutor, maxParallelTools, ioTypeIndex,
                canDispatch, request -> executeOne(roundCtx, request, compressionOptions));
    }

    List<ToolExecutionOutcome> executeBatch(
            List<ToolExecutionRequest> requests,
            ToolResultCompressionOptions compressionOptions,
            Executor parallelExecutor,
            int maxParallelTools,
            Map<String, ToolIoTypeEnum> ioTypeIndex,
            BooleanSupplier canDispatch,
            Function<ToolExecutionRequest, ToolExecutionOutcome> execute) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }

        List<ToolExecutionOutcome> outcomes = new ArrayList<>(requests.size());
        boolean interrupted = false;
        int index = 0;
        while (index < requests.size()) {
            ToolExecutionRequest request = requests.get(index);
            if (interrupted) {
                outcomes.add(notDispatched(request, compressionOptions, "SKIPPED", "本批次有工具超时或触发安全中断，后续调用已跳过"));
                index++;
                continue;
            }
            if (parallelExecutor == null || !isReadOnly(request, ioTypeIndex)) {
                ToolExecutionOutcome outcome = executeIfAllowed(request, compressionOptions, canDispatch, execute);
                outcomes.add(outcome);
                interrupted = isSecurityInterrupt(outcome);
                index++;
                continue;
            }

            // 等待当前批次的读取完成后再启动后继工具，保留写入与依赖读取的相对顺序。
            int limit = maxParallelTools > 0 ? maxParallelTools : requests.size();
            int end = index + 1;
            while (end < requests.size() && end - index < limit && isReadOnly(requests.get(end), ioTypeIndex)) {
                end++;
            }
            List<ToolExecutionOutcome> batch = submitReadBatch(requests.subList(index, end),
                    compressionOptions, parallelExecutor, canDispatch, execute);
            outcomes.addAll(batch);
            interrupted = batch.stream().anyMatch(outcome -> isSecurityInterrupt(outcome)
                    || "TIMEOUT".equals(outcome.resultMessage().attributes().get("dispatchStatus")));
            index = end;
        }
        return outcomes;
    }

    private boolean isReadOnly(ToolExecutionRequest request, Map<String, ToolIoTypeEnum> ioTypeIndex) {
        return ioTypeIndex != null && ioTypeIndex.get(request.name()) == ToolIoTypeEnum.READ;
    }

    private boolean isSecurityInterrupt(ToolExecutionOutcome outcome) {
        String action = outcome.toolResult().getControlAction();
        return SecurityComplianceConstant.DECISION_PAUSE.equals(action)
                || SecurityComplianceConstant.DECISION_BLOCK.equals(action);
    }

    private List<ToolExecutionOutcome> submitReadBatch(
            List<ToolExecutionRequest> requests,
            ToolResultCompressionOptions options,
            Executor executor,
            BooleanSupplier canDispatch,
            Function<ToolExecutionRequest, ToolExecutionOutcome> execute) {
        List<CompletableFuture<ToolExecutionOutcome>> futures = new ArrayList<>(requests.size());
        for (ToolExecutionRequest request : requests) {
            try {
                var completion = new CompletableFuture<ToolExecutionOutcome>();
                var task = new FutureTask<Void>(() -> {
                    try {
                        completion.complete(executeIfAllowed(request, options, canDispatch, execute));
                    } catch (RuntimeException failure) {
                        completion.completeExceptionally(failure);
                    }
                    return null;
                });
                executor.execute(task);
                futures.add(completion.orTimeout(parallelTimeoutSeconds, TimeUnit.SECONDS)
                        .exceptionally(failure -> {
                            if (!(failure instanceof TimeoutException)) {
                                throw new CompletionException(failure);
                            }
                            task.cancel(true);
                            ToolResult result = buildFailureResult(request.id(), request.name(), "只读工具执行超时");
                            return buildOutcome(request, result, Map.of("dispatchStatus", "TIMEOUT"), true,
                                    null, options, LocalDateTime.now());
                        }));
            } catch (RejectedExecutionException rejected) {
                // 已提交的请求仍可完成，拒绝只影响当前未提交的工具。
                futures.add(CompletableFuture.completedFuture(
                        notDispatched(request, options, "BUSY", "工具执行队列繁忙，此调用未启动")));
            }
        }
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private ToolExecutionOutcome executeIfAllowed(ToolExecutionRequest request,
                                                 ToolResultCompressionOptions options,
                                                 BooleanSupplier canDispatch,
                                                 Function<ToolExecutionRequest, ToolExecutionOutcome> execute) {
        if (!canDispatch.getAsBoolean()) {
            return notDispatched(request, options, "SKIPPED", "执行已停止，此调用已跳过");
        }
        return execute.apply(request);
    }

    private ToolExecutionOutcome notDispatched(ToolExecutionRequest request,
                                              ToolResultCompressionOptions options,
                                              String dispatchStatus,
                                              String reason) {
        ToolResult failure = buildFailureResult(request.id(), request.name(), reason);
        return new ToolExecutionOutcome(failure,
                buildResultMessage(request, failure, Map.of("dispatchStatus", dispatchStatus), true, null, options),
                null, 0L);
    }

    private long elapsed(long startMs) {
        return System.currentTimeMillis() - startMs;
    }

    private ToolExecutionOutcome buildOutcome(ToolExecutionRequest request,
                                              ToolResult toolResult,
                                              Map<String, Object> attributes,
                                              boolean isError,
                                              String resultText,
                                              ToolResultCompressionOptions options,
                                              LocalDateTime startedAt) {
        long durationMs = Math.max(0L, Duration.between(startedAt, LocalDateTime.now()).toMillis());
        return new ToolExecutionOutcome(
                toolResult,
                buildResultMessage(request, toolResult, attributes, isError, resultText, options),
                startedAt,
                durationMs
        );
    }

    /** 工具执行结果封装（工具结果 + ChatMemory 消息） */
    public record ToolExecutionOutcome(ToolResult toolResult,
                                       ToolExecutionResultMessage resultMessage,
                                       LocalDateTime startedAt,
                                       long durationMs) {

        public boolean wasDispatched() {
            return startedAt != null;
        }
    }

    public record ToolResultCompressionOptions(int maxMessageChars, int requestSummaryLimit) {

        public static ToolResultCompressionOptions defaults() {
            return new ToolResultCompressionOptions(12_000, 240);
        }
    }

    private record ToolResultCompressionResult(String text, int rawChars, int compressedChars, boolean compressionApplied) {}
}
