package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.AgentLoopPromptConstant;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.langchain4j.AgentToolSessionFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
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
import java.util.concurrent.Executor;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Agentic Loop 工具执行处理器
 * <p>负责工具调用的过滤、限流、执行及结果序列化，从 AgentExecutionEngine 中拆分出来</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoopToolHandler {

    private final ObjectMapper objectMapper;

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
            return buildOutcome(request, toolResult,
                    result != null ? result.attributes() : Map.of(),
                    result != null ? result.isError() : !toolResult.isSuccess(),
                    result != null ? result.resultText() : null,
                    options,
                    startedAt);
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

    /**
     * 并行执行工具调用（预分区：读工具一次性并行，写工具按原始顺序串行）
     *
     * @param ioTypeIndex 工具 IO 类型索引（来自 DB 配置）
     */
    public List<ToolExecutionOutcome> executeParallel(
            AgentToolSessionFactory.RoundToolContext roundCtx,
            List<ToolExecutionRequest> requests,
            ToolResultCompressionOptions compressionOpts,
            Executor parallelExecutor,
            int maxParallelTools,
            Map<String, ToolIoTypeEnum> ioTypeIndex) {

        if (requests == null || requests.isEmpty()) return List.of();
        if (requests.size() == 1) {
            return List.of(executeOne(roundCtx, requests.get(0), compressionOpts));
        }

        // 预分区：READ 工具并行，WRITE / READ_WRITE 工具串行
        List<int[]> readGroup = new ArrayList<>();   // [originalIndex]
        List<int[]> writeGroup = new ArrayList<>();
        List<ToolExecutionRequest> readRequests = new ArrayList<>();

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            ToolIoTypeEnum ioType = ioTypeIndex != null
                    ? ioTypeIndex.getOrDefault(req.name(), ToolIoTypeEnum.READ_WRITE)
                    : ToolIoTypeEnum.READ_WRITE;
            if (ioType == ToolIoTypeEnum.READ) {
                readGroup.add(new int[]{i});
                readRequests.add(req);
            } else {
                writeGroup.add(new int[]{i});
            }
        }

        ToolExecutionOutcome[] results = new ToolExecutionOutcome[requests.size()];

        // 1. 所有读工具一次性并行提交
        if (!readRequests.isEmpty()) {
            List<ToolExecutionOutcome> readResults = runParallel(
                    roundCtx, readRequests, compressionOpts, parallelExecutor, maxParallelTools);
            for (int j = 0; j < readGroup.size(); j++) {
                results[readGroup.get(j)[0]] = readResults.get(j);
            }
        }

        // 2. 写工具按原始顺序串行执行
        for (int[] entry : writeGroup) {
            results[entry[0]] = executeOne(roundCtx, requests.get(entry[0]), compressionOpts);
        }

        return List.of(results);
    }

    private List<ToolExecutionOutcome> runParallel(
            AgentToolSessionFactory.RoundToolContext roundCtx,
            List<ToolExecutionRequest> group,
            ToolResultCompressionOptions opts,
            Executor executor,
            int maxParallelTools) {
        try {
            if (maxParallelTools > 0 && group.size() > maxParallelTools) {
                List<ToolExecutionOutcome> batchResults = new ArrayList<>(group.size());
                for (int i = 0; i < group.size(); i += maxParallelTools) {
                    List<ToolExecutionRequest> batch = group.subList(i, Math.min(i + maxParallelTools, group.size()));
                    batchResults.addAll(doRunParallel(roundCtx, batch, opts, executor));
                }
                return batchResults;
            }
            return doRunParallel(roundCtx, group, opts, executor);
        } catch (Exception e) {
            log.warn("[工具并行执行] 并行提交失败，降级为串行执行: {}", e.getMessage());
            return group.stream()
                    .map(req -> executeOne(roundCtx, req, opts))
                    .toList();
        }
    }

    private List<ToolExecutionOutcome> doRunParallel(
            AgentToolSessionFactory.RoundToolContext roundCtx,
            List<ToolExecutionRequest> group,
            ToolResultCompressionOptions opts,
            Executor executor) {
        List<CompletableFuture<ToolExecutionOutcome>> futures = group.stream()
                .map(req -> CompletableFuture.supplyAsync(() -> executeOne(roundCtx, req, opts), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
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
                                       long durationMs) {}

    public record ToolResultCompressionOptions(int maxMessageChars, int requestSummaryLimit) {

        public static ToolResultCompressionOptions defaults() {
            return new ToolResultCompressionOptions(12_000, 240);
        }
    }

    private record ToolResultCompressionResult(String text, int rawChars, int compressedChars, boolean compressionApplied) {}
}
