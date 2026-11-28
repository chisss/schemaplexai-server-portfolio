package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.ChatMessageText;
import com.schemaplexai.service.ai.ToolNameNormalizer;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_CALL_ID;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_MAVEN_MODEL_SUMMARY;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_READ_EVIDENCE_HASH;

/** 为候选模型组装请求窗口，保留最近任务和每组工具调用的完整性。 */
final class AgentModelRequestPlanner {
    private static final int MIN_OUTPUT_TOKENS = 128;
    private static final int DEFAULT_MAX_OUTPUT = 2048;
    private static final int TOOL_SCHEMA_OVERHEAD = 12;
    private static final int MIN_DUPLICATE_RESULT_CHARS = 300;
    private static final int MIN_MAVEN_RESULT_SAVINGS_CHARS = 300;
    private static final int MIN_WRITE_ARGUMENT_SAVINGS_CHARS = 300;
    private static final int RECENT_COMPLETE_EXCHANGES = 2;
    private static final String TASK_CONTEXT_PROJECTION = "schemaplexai.taskContextProjection";
    private static final String DESKTOP_EVIDENCE_PROJECTION = "schemaplexai.desktopEvidenceProjection";
    private static final String TOOL_ARGUMENT_PREFIX = "请求参数: ";
    private static final String TOOL_RESULT_PREFIX = "工具结果: ";
    private static final Pattern SAFE_TOOL_CALL_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern DESKTOP_RECEIPT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern ANSI_COLOR = Pattern.compile("\u001b\\[[0-9;]*m");
    private static final Set<String> DESKTOP_READ_ONLY_TOOLS =
            Set.of("sys_read", "sys_ls", "sys_stat", "sys_glob", "sys_grep");
    private static final Set<String> DESKTOP_READ_ARGUMENTS =
            Set.of("path", "startLine", "endLine", "workingDirectory", "workdir");
    private static final ObjectMapper ARGUMENTS_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final TokenEstimatorSupport estimator;

    AgentModelRequestPlanner(TokenEstimatorSupport estimator) {
        this.estimator = estimator;
    }

    ChatRequest fit(ChatRequest request, AiModelConfig config, long remainingTokens) {
        return fit(request, config, remainingTokens, findTaskContextProjection(request.messages()));
    }

    private ChatRequest fit(ChatRequest request, AiModelConfig config, long remainingTokens,
                            TaskContextProjection taskContext) {
        var desktopEvidence = findDesktopEvidenceProjection(request.messages());
        if (config == null && taskContext == null && desktopEvidence == null) {
            return request;
        }
        List<ChatMessage> messages = new ArrayList<>(request.messages());
        if (taskContext != null) {
            messages.set(0, taskContext.system());
        }
        if (desktopEvidence != null && taskContext == null) {
            messages.set(0, withoutDesktopEvidenceMarker((SystemMessage) messages.getFirst()));
        }
        if (config == null) {
            return request.toBuilder().messages(messages).build();
        }
        int modelWindow = config.resolvedContextWindowTokens();
        int totalBudget = (int) Math.min(modelWindow, remainingTokens);
        int defaultOutput = config.getMaxTokens() > 0 ? config.getMaxTokens() : DEFAULT_MAX_OUTPUT;
        int requestedOutput = request.maxOutputTokens() != null && request.maxOutputTokens() > 0
                ? Math.min(request.maxOutputTokens(), defaultOutput) : defaultOutput;
        int outputReserve = Math.min(requestedOutput, MIN_OUTPUT_TOKENS);
        int verifiedTaskStart = desktopEvidence == null ? -1 : findVerifiedDesktopTask(messages, desktopEvidence);
        Set<String> referencedReads = projectSupersededDesktopWrites(messages);
        replaceDuplicateDesktopReads(messages, referencedReads);
        if (verifiedTaskStart >= 0) projectVerifiedDesktopReads(messages, verifiedTaskStart, referencedReads);
        projectDesktopMavenResults(messages);
        projectDuplicateDesktopReceiptArguments(messages);
        boolean taskContextRemoved = taskContext != null && taskContext.removeFrom(messages);
        List<ToolSpecification> tools = request.toolSpecifications() == null ? List.of() : request.toolSpecifications();
        int margin = margin(totalBudget);
        long inputTokens = estimateInput(config, messages, tools) + margin;
        List<String> discardedContext = new ArrayList<>();
        while (inputTokens + outputReserve > totalBudget) {
            int discarded = discardOldestTurn(config, messages, discardedContext);
            if (discarded == 0) {
                break;
            }
            inputTokens -= discarded;
            if (taskContextRemoved && !taskContext.hasAuthoredUser(messages)) {
                messages.set(0, taskContext.system());
                inputTokens = estimateInput(config, messages, tools) + margin;
                taskContextRemoved = false;
            }
        }
        if (inputTokens + outputReserve > totalBudget) {
            if (remainingTokens < modelWindow && inputTokens - margin + outputReserve + margin(modelWindow) <= modelWindow) {
                throw new IncompatibleCandidateException("剩余 Token 预算不足以发送模型请求", true);
            }
            throw new IncompatibleCandidateException("候选模型窗口无法容纳当前任务、工具定义和最小输出");
        }
        if (!discardedContext.isEmpty()) {
            UserMessage summary = UserMessage.from("[早期对话节选]\n" + String.join("\n", discardedContext));
            long summaryTokens = estimator.estimateMessage(config, summary);
            if (inputTokens + summaryTokens + requestedOutput <= totalBudget) {
                int index = 0;
                while (index < messages.size() && messages.get(index) instanceof SystemMessage) index++;
                messages.add(index, summary);
                inputTokens += summaryTokens;
            }
        }
        if (!config.isMultimodal() && messages.stream().anyMatch(this::containsMedia)) {
            throw new IncompatibleCandidateException("当前会话包含多模态内容，请使用支持该内容的模型");
        }
        return request.toBuilder().messages(messages)
                .maxOutputTokens((int) Math.min(requestedOutput, totalBudget - inputTokens))
                .build();
    }

    private int margin(int totalBudget) {
        return Math.min(512, Math.max(4, totalBudget / 32));
    }

    /** 标记仅存在于发往窗口规划器的请求副本中。 */
    static SystemMessage markTaskContext(SystemMessage system, UserMessage authoredUser,
                                         ContextInjector.TaskContextRange range) {
        if (range == null || authoredUser == null || !authoredUser.hasSingleText()
                || !authoredUser.singleText().equals(range.exactUserText())
                || system.attributes().containsKey(TASK_CONTEXT_PROJECTION)) {
            return system;
        }
        var attributes = new HashMap<>(system.attributes());
        attributes.put(TASK_CONTEXT_PROJECTION, new TaskContextMarker(authoredUser, range));
        return system.toBuilder().attributes(attributes).build();
    }

    /** 仅在请求副本可投影本轮桌面证据，原始系统指令和会话存档保持完整。 */
    static SystemMessage markDesktopEvidenceProjection(SystemMessage system, UserMessage authoredUser) {
        if (authoredUser == null || !authoredUser.hasSingleText()) return system;
        var attributes = new HashMap<>(system.attributes());
        attributes.put(DESKTOP_EVIDENCE_PROJECTION, new DesktopEvidenceMarker(authoredUser));
        return system.toBuilder().attributes(attributes).build();
    }

    private DesktopEvidenceMarker findDesktopEvidenceProjection(List<ChatMessage> messages) {
        return !messages.isEmpty() && messages.getFirst() instanceof SystemMessage system
                && system.attributes().get(DESKTOP_EVIDENCE_PROJECTION) instanceof DesktopEvidenceMarker marker
                ? marker : null;
    }

    private SystemMessage withoutDesktopEvidenceMarker(SystemMessage system) {
        var attributes = new HashMap<>(system.attributes());
        attributes.remove(DESKTOP_EVIDENCE_PROJECTION);
        return system.toBuilder().attributes(attributes).build();
    }

    private record DesktopEvidenceMarker(UserMessage authoredUser) {
        @Override
        public String toString() {
            return "DesktopEvidenceMarker";
        }
    }

    private TaskContextProjection findTaskContextProjection(List<ChatMessage> messages) {
        if (messages.isEmpty() || !(messages.getFirst() instanceof SystemMessage system)
                || !(system.attributes().get(TASK_CONTEXT_PROJECTION) instanceof TaskContextMarker marker)) {
            return null;
        }
        var attributes = new HashMap<>(system.attributes());
        attributes.remove(TASK_CONTEXT_PROJECTION);
        attributes.remove(DESKTOP_EVIDENCE_PROJECTION);
        return new TaskContextProjection(system.toBuilder().attributes(attributes).build(), marker);
    }

    private record TaskContextMarker(UserMessage authoredUser, ContextInjector.TaskContextRange range) {
        @Override
        public String toString() {
            return "TaskContextMarker";
        }
    }

    private record TaskContextProjection(SystemMessage system, TaskContextMarker marker) {
        boolean hasAuthoredUser(List<ChatMessage> messages) {
            return messages.stream().anyMatch(message -> message == marker.authoredUser());
        }

        boolean removeFrom(List<ChatMessage> messages) {
            if (!hasAuthoredUser(messages)) {
                return false;
            }
            String shortened = marker.range().removeFrom(system.text());
            if (shortened.equals(system.text()) || shortened.isBlank()) {
                return false;
            }
            messages.set(0, system.toBuilder().text(shortened).build());
            return true;
        }
    }

    private void projectDesktopMavenResults(List<ChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof ToolExecutionResultMessage result)) continue;
            String projection = desktopMavenProjection(result);
            if (projection != null) {
                var attributes = new HashMap<>(result.attributes());
                attributes.remove(DESKTOP_MAVEN_MODEL_SUMMARY);
                messages.set(index, result.toBuilder().contents(TextContent.from(projection)).attributes(attributes).build());
            }
        }
    }

    static String desktopMavenProjection(ToolExecutionResultMessage result) {
        if (!"sys_bash".equals(result.toolName()) || result.isError() == null
                || !result.hasSingleText() || result.text() == null
                || !(result.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY) instanceof String summary)) {
            return null;
        }
        if (Boolean.TRUE.equals(result.isError())
                && !(result.attributes().get(DESKTOP_CALL_ID) instanceof String brokerId && !brokerId.isBlank())) {
            return null;
        }
        String projection = "桌面 Maven 工具回执摘要（原日志保留）: " + summary;
        return result.text().length() - projection.length() >= MIN_MAVEN_RESULT_SAVINGS_CHARS ? projection : null;
    }

    private void replaceDuplicateDesktopReads(List<ChatMessage> messages, Set<String> referencedReads) {
        Map<String, ToolExecutionResultMessage> latestReads = new HashMap<>();
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (!(messages.get(index) instanceof ToolExecutionResultMessage read)
                    || !"sys_read".equals(read.toolName()) || !Boolean.FALSE.equals(read.isError())
                    || !read.hasSingleText() || read.text() == null || read.text().length() < MIN_DUPLICATE_RESULT_CHARS
                    || read.id() == null || !SAFE_TOOL_CALL_ID.matcher(read.id()).matches()
                    || !(read.attributes().get(DESKTOP_READ_EVIDENCE_HASH) instanceof String hash)) {
                continue;
            }
            ToolExecutionResultMessage latest = latestReads.putIfAbsent(hash, read);
            if (latest != null && !referencedReads.contains(read.id()) && !latest.id().equals(read.id())
                    && sameCompleteRead(read, latest)) {
                String reference = "文件读取成功，参数与结果同后续调用 " + latest.id() + "，请参照该调用的完整结果。";
                messages.set(index, read.toBuilder().contents(TextContent.from(reference)).build());
            }
        }
    }

    private boolean sameCompleteRead(ToolExecutionResultMessage older, ToolExecutionResultMessage newer) {
        if (!older.attributes().containsKey(DESKTOP_CALL_ID) && !newer.attributes().containsKey(DESKTOP_CALL_ID)) {
            // 兼容只带读取指纹的旧回执，继续使用已有的长度判据。
            return !Boolean.TRUE.equals(older.attributes().get("compressionApplied"))
                    && !Boolean.TRUE.equals(newer.attributes().get("compressionApplied"))
                    && !older.text().contains("\n[已压缩，原始长度: ")
                    && !newer.text().contains("\n[已压缩，原始长度: ")
                    && newer.text().length() >= older.text().length();
        }
        String olderContent = completeSuccessfulDesktopResult(older);
        return olderContent != null && olderContent.equals(completeSuccessfulDesktopResult(newer));
    }

    private int findVerifiedDesktopTask(List<ChatMessage> messages, DesktopEvidenceMarker marker) {
        int taskStart = messages.lastIndexOf(marker.authoredUser());
        if (taskStart < 0 || messages.get(taskStart) != marker.authoredUser()) return -1;
        int index = messages.size() - 1;
        while (index > taskStart) {
            var message = messages.get(index--);
            if (message instanceof UserMessage) return -1;
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) return -1;
            if (!(message instanceof ToolExecutionResultMessage)) continue;
            while (index > taskStart && messages.get(index) instanceof ToolExecutionResultMessage) index--;
            if (!(messages.get(index) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) return -1;
            var calls = ai.toolExecutionRequests();
            var receipts = completeReceipts(messages, index--, calls);
            if (receipts.isEmpty()) return -1;
            boolean verifiedBuild = false;
            for (var call : calls) {
                var receipt = receipts.get(call.id());
                if (!verifiedBuild && isVerifiedBuild(call, receipt)) {
                    verifiedBuild = true;
                } else if (!DESKTOP_READ_ONLY_TOOLS.contains(call.name())
                        || completeSuccessfulDesktopResult(receipt) == null) {
                    return -1;
                }
            }
            if (verifiedBuild) return taskStart;
        }
        return -1;
    }

    private boolean isVerifiedBuild(ToolExecutionRequest call, ToolExecutionResultMessage receipt) {
        if (!"sys_bash".equals(call.name())
                || !(receipt.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY) instanceof String summary)) return false;
        String result = completeSuccessfulDesktopResult(receipt);
        var args = desktopArguments(call);
        if (result == null || args == null || !args.path("command").isTextual()) return false;
        try {
            var facts = ARGUMENTS_JSON.readTree(summary);
            var output = ARGUMENTS_JSON.readTree(result);
            return facts != null && output != null
                    && zeroExitCode(facts.path("exitCode")) && zeroExitCode(output.path("exitCode"))
                    && args.path("command").textValue().strip().equals(facts.path("command").textValue())
                    && (facts.path("logEnd").asText("").lines().anyMatch("[INFO] BUILD SUCCESS"::equals)
                        || isQuietBuild(facts, output));
        } catch (JsonProcessingException ignored) {
            return false;
        }
    }

    private boolean zeroExitCode(JsonNode exitCode) {
        return exitCode.isIntegralNumber() && exitCode.canConvertToInt() && exitCode.intValue() == 0;
    }

    private boolean isQuietBuild(JsonNode facts, JsonNode output) {
        return facts.path("quietBuild").isBoolean() && facts.path("quietBuild").booleanValue()
                && output.path("stdout").isTextual()
                && ANSI_COLOR.matcher(output.path("stdout").textValue()).replaceAll("").isBlank()
                && output.path("stderr").isTextual()
                && ANSI_COLOR.matcher(output.path("stderr").textValue()).replaceAll("").lines().map(String::strip)
                        .allMatch(line -> line.isEmpty() || line.startsWith("NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home="))
                && !output.path("stdoutTruncated").asBoolean() && !output.path("stderrTruncated").asBoolean();
    }

    private void projectVerifiedDesktopReads(List<ChatMessage> messages, int taskStart, Set<String> referencedReads) {
        int exchanges = 0;
        for (int index = messages.size() - 1; index > taskStart; index--) {
            if (!(messages.get(index) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) continue;
            var calls = ai.toolExecutionRequests();
            if (completeReceipts(messages, index, calls).isEmpty() || ++exchanges <= RECENT_COMPLETE_EXCHANGES) continue;
            var reads = new HashMap<String, ToolExecutionRequest>();
            for (var call : calls) {
                if ("sys_read".equals(call.name()) && !referencedReads.contains(call.id())) reads.put(call.id(), call);
            }
            for (int offset = 1; offset <= calls.size(); offset++) {
                var receipt = (ToolExecutionResultMessage) messages.get(index + offset);
                var call = reads.get(receipt.id());
                if (call == null) continue;
                String projection = verifiedDesktopReadProjection(call, receipt);
                if (projection != null) messages.set(index + offset, receipt.toBuilder()
                        .contents(TextContent.from(projection)).build());
            }
        }
    }

    private String verifiedDesktopReadProjection(ToolExecutionRequest call, ToolExecutionResultMessage receipt) {
        var args = desktopArguments(call);
        String path = desktopPath(args);
        if (path == null || path.length() > 240 || receipt.text() == null
                || receipt.text().length() < MIN_DUPLICATE_RESULT_CHARS || verifiedReadContent(args, receipt) == null) {
            return null;
        }
        var note = ARGUMENTS_JSON.createObjectNode().put("path", path).put("callId", call.id())
                .put("fingerprint", (String) receipt.attributes().get(DESKTOP_READ_EVIDENCE_HASH));
        for (String bound : List.of("startLine", "endLine")) {
            if (args.has(bound)) {
                var value = args.get(bound);
                if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) return null;
                note.set(bound, value);
            }
        }
        int expectedArgCount = note.size() - 2 + (args.has("workingDirectory") || args.has("workdir") ? 1 : 0);
        if (args.size() != expectedArgCount) return null;
        String projection = "早期桌面文件读取（原文已归档，需要源码时读取当前文件）：" + note;
        return receipt.text().length() - projection.length() >= MIN_DUPLICATE_RESULT_CHARS ? projection : null;
    }

    private Set<String> projectSupersededDesktopWrites(List<ChatMessage> messages) {
        // 一次用户任务只绑定一个本机工作区。保留之前的完整轮次。
        int turnStart = messages.size() - 1;
        while (turnStart >= 0 && !(messages.get(turnStart) instanceof UserMessage)) {
            turnStart--;
        }
        if (turnStart < 0) return Set.of();
        Map<String, String> newerWriteIds = new HashMap<>();
        Map<String, String> verifiedReplacementWrites = new HashMap<>();
        var readEvidence = new DesktopReadEvidence();
        Set<String> referencedReads = new HashSet<>();
        Map<String, ToolExecutionResultMessage> olderReadProjections = new HashMap<>();
        int completeExchanges = 0;
        for (int index = messages.size() - 1; index > turnStart; index--) {
            ChatMessage message = messages.get(index);
            if (!(message instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) {
                continue;
            }
            var calls = ai.toolExecutionRequests();
            var receipts = completeReceipts(messages, index, calls);
            readEvidence.prepareExchange(calls, receipts);
            boolean clearReplacementWrites = receipts.isEmpty()
                    || calls.stream().anyMatch(call -> !DESKTOP_READ_ONLY_TOOLS.contains(call.name())
                            && trustedDesktopWrite(call, receipts.get(call.id())) == null);
            if (clearReplacementWrites) verifiedReplacementWrites.clear();
            if (receipts.isEmpty()) continue;
            var projected = new ArrayList<>(calls);
            Map<String, String> writtenInExchange = new HashMap<>();
            Map<String, String> verifiedInExchange = new HashMap<>();
            Set<String> pathsInExchange = new HashSet<>();
            Set<String> unorderedPaths = new HashSet<>();
            for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                var call = calls.get(callIndex);
                var receipt = receipts.get(call.id());
                var write = trustedDesktopWrite(call, receipt);
                if (write == null) continue;
                if (!pathsInExchange.add(write.path())) {
                    unorderedPaths.add(write.path());
                }
                if (completeExchanges >= RECENT_COMPLETE_EXCHANGES) {
                    projected.set(callIndex, projectHistoricalWrite(call, receipt, write,
                            newerWriteIds, readEvidence, referencedReads));
                }
                if (write.full() && call == projected.get(callIndex)) {
                    writtenInExchange.put(write.path(), call.id());
                    if (successfulDesktopWrite(receipt, write)) {
                        verifiedInExchange.put(write.path(), call.id());
                    }
                }
            }
            if (completeExchanges >= RECENT_COMPLETE_EXCHANGES) {
                for (var call : calls) {
                    if (!"sys_read".equals(call.name())) continue;
                    var arguments = desktopArguments(call);
                    String path = desktopPath(arguments);
                    if (path == null || pathsInExchange.contains(path)) continue;
                    String replacement = verifiedReplacementWrites.get(path);
                    var receipt = receipts.get(call.id());
                    if (replacement != null && receipt.hasSingleText() && receipt.text() != null
                            && receipt.text().length() >= MIN_DUPLICATE_RESULT_CHARS
                            && verifiedFullRead(arguments, receipt) != null) {
                        String note = "此处读取的是写入前版本。后续完整文件正文见已成功保存的调用 " + replacement + "。";
                        olderReadProjections.put(receipt.id(), receipt.toBuilder().contents(TextContent.from(note)).build());
                    }
                }
            }
            if (!calls.equals(projected)) {
                messages.set(index, ai.toBuilder().toolExecutionRequests(projected).build());
            }
            // 同一模型回合的多个工具可能并行运行，不能断定其先后。
            unorderedPaths.forEach(writtenInExchange::remove);
            writtenInExchange.forEach(newerWriteIds::putIfAbsent);
            pathsInExchange.forEach(verifiedReplacementWrites::remove);
            unorderedPaths.forEach(verifiedInExchange::remove);
            if (!clearReplacementWrites) verifiedInExchange.forEach(verifiedReplacementWrites::putIfAbsent);
            readEvidence.finishExchange(calls, receipts);
            completeExchanges++;
        }
        for (int index = turnStart + 1; index < messages.size(); index++) {
            if (messages.get(index) instanceof ToolExecutionResultMessage receipt
                    && olderReadProjections.containsKey(receipt.id()) && !referencedReads.contains(receipt.id())) {
                messages.set(index, olderReadProjections.get(receipt.id()));
            }
        }
        return referencedReads;
    }

    private ToolExecutionRequest projectHistoricalWrite(ToolExecutionRequest call, ToolExecutionResultMessage receipt,
                                                        DesktopWrite write, Map<String, String> newerWriteIds,
                                                        DesktopReadEvidence readEvidence, Set<String> referencedReads) {
        String newerWrite = write.full() ? newerWriteIds.get(write.path()) : null;
        if (newerWrite != null) {
            return projectWrite(call, write, "后续完整写入见工具调用 " + newerWrite);
        }
        var read = readEvidence.forPath(write.path());
        if (read == null || !write.matches(read.content()) || !successfulDesktopWrite(receipt, write)) {
            return call;
        }
        var projected = projectWrite(call, write, "完整文件见后续读取工具调用 " + read.callId());
        if (projected != call) {
            referencedReads.add(read.callId());
        }
        return projected;
    }

    private Map<String, ToolExecutionResultMessage> completeReceipts(List<ChatMessage> messages, int aiIndex,
                                                                     List<ToolExecutionRequest> calls) {
        Map<String, ToolExecutionResultMessage> receipts = new HashMap<>();
        int next = aiIndex + 1;
        while (next < messages.size() && messages.get(next) instanceof ToolExecutionResultMessage receipt) {
            if (receipt.id() == null || receipts.putIfAbsent(receipt.id(), receipt) != null) return Map.of();
            next++;
        }
        if (receipts.size() != calls.size()) return Map.of();
        Set<String> requestIds = new HashSet<>();
        for (var call : calls) {
            var receipt = receipts.get(call.id());
            if (receipt == null || !requestIds.add(call.id()) || !Objects.equals(call.name(), receipt.toolName())) {
                return Map.of();
            }
        }
        return receipts;
    }

    private DesktopWrite trustedDesktopWrite(ToolExecutionRequest call, ToolExecutionResultMessage receipt) {
        if (receipt == null || !isDesktopWriteTool(call)
                || call.id() == null || !SAFE_TOOL_CALL_ID.matcher(call.id()).matches()
                || !Boolean.FALSE.equals(receipt.isError())
                || !(receipt.attributes().get(DESKTOP_CALL_ID) instanceof String brokerId)
                || !DESKTOP_RECEIPT_ID.matcher(brokerId).matches() || call.arguments() == null) {
            return null;
        }
        var args = desktopArguments(call);
        String path = desktopPath(args);
        if (path == null) return null;
        int operationFields = args.size() - (args.has("workingDirectory") || args.has("workdir") ? 2 : 1);
        if (operationFields == 1 && args.path("content").isTextual()) {
            return new DesktopWrite(path, args, true);
        }
        if ("sys_edit".equals(call.name()) && operationFields == 2 && args.path("oldText").isTextual()
                && args.path("newText").isTextual() && !args.path("oldText").textValue().isEmpty()) {
            return new DesktopWrite(path, args, false);
        }
        return null;
    }

    private ToolExecutionRequest projectWrite(ToolExecutionRequest call, DesktopWrite write, String reference) {
        String note = "[历史写入正文已省略，" + reference + "]";
        if (write.full()) {
            write.arguments().put("content", note);
        } else {
            write.arguments().put("oldText", "[历史替换原文已省略]");
            write.arguments().put("newText", note);
        }
        String compact = write.arguments().toString();
        if (call.arguments().length() - compact.length() < MIN_WRITE_ARGUMENT_SAVINGS_CHARS) {
            return call;
        }
        return call.toBuilder().arguments(compact).build();
    }

    private boolean isDesktopWriteTool(ToolExecutionRequest call) {
        return "sys_write".equals(call.name()) || "sys_edit".equals(call.name());
    }

    private ObjectNode desktopArguments(ToolExecutionRequest call) {
        if (call.arguments() == null) return null;
        try {
            return ARGUMENTS_JSON.readTree(call.arguments()) instanceof ObjectNode args ? args : null;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String desktopPath(ObjectNode args) {
        if (args == null || !args.path("path").isTextual()
                || (args.has("workingDirectory") && args.has("workdir"))) return null;
        var directory = args.has("workingDirectory") ? args.get("workingDirectory") : args.get("workdir");
        if (directory != null && !directory.isTextual()) return null;
        String path = args.path("path").textValue();
        String workdir = directory == null ? "." : directory.textValue();
        if (path.isBlank() || workdir.isBlank() || path.startsWith("/") || workdir.startsWith("/")
                || path.startsWith("~") || workdir.startsWith("~")) return null;
        var segments = new ArrayList<String>();
        for (String segment : (workdir + "/" + path).split("/")) {
            if (segment.equals("..") || segment.contains("\\") || segment.contains(":")
                    || segment.chars().anyMatch(Character::isISOControl)) return null;
            if (!segment.isEmpty() && !segment.equals(".")) segments.add(segment);
        }
        return segments.isEmpty() ? null : String.join("/", segments);
    }

    private boolean successfulDesktopWrite(ToolExecutionResultMessage receipt, DesktopWrite write) {
        String expected = "已保存 " + write.arguments().path("path").textValue();
        return expected.equals(completeSuccessfulDesktopResult(receipt));
    }

    private String completeSuccessfulDesktopResult(ToolExecutionResultMessage receipt) {
        if (receipt == null || !Boolean.FALSE.equals(receipt.isError())) return null;
        int bodyStart = completeDesktopReceiptBodyStart(receipt);
        if (bodyStart < 0) return null;
        try {
            var envelope = ARGUMENTS_JSON.readTree(receipt.text().substring(bodyStart));
            if (matchingDesktopEnvelope(envelope, receipt) && !envelope.hasNonNull("errorMessage")
                    && !envelope.hasNonNull("controlAction")) {
                return envelope.path("result").textValue();
            }
        } catch (JsonProcessingException ignored) {
            // 无法验证完整性的回执保留原文。
        }
        return null;
    }

    private record DesktopWrite(String path, ObjectNode arguments, boolean full) {
        boolean matches(String readContent) {
            if (full) return readContent.equals(arguments.path("content").textValue());
            String newText = arguments.path("newText").textValue();
            int position = readContent.indexOf(newText);
            return !newText.isBlank() && position >= 0 && readContent.indexOf(newText, position + 1) < 0;
        }
    }

    private record DesktopFileRead(String callId, String content) {}

    private String verifiedFullRead(ObjectNode args, ToolExecutionResultMessage receipt) {
        if (args.size() != (args.has("workingDirectory") || args.has("workdir") ? 2 : 1)) return null;
        return verifiedReadContent(args, receipt);
    }

    private String verifiedReadContent(ObjectNode args, ToolExecutionResultMessage receipt) {
        String content = completeSuccessfulDesktopResult(receipt);
        return matchesDesktopReadEvidence(args, receipt, content) ? content : null;
    }

    private boolean matchesDesktopReadEvidence(ObjectNode args, ToolExecutionResultMessage receipt, String content) {
        if (content == null || content.endsWith("\n[已截断]")) return false;
        String evidence = ARGUMENTS_JSON.createArrayNode().add(args).add(content).toString();
        return HashUtils.sha256Hex(evidence.getBytes(StandardCharsets.UTF_8))
                .equals(receipt.attributes().get(DESKTOP_READ_EVIDENCE_HASH));
    }

    private final class DesktopReadEvidence {
        private final Map<String, DesktopFileRead> reads = new HashMap<>();
        private final Set<String> knownPaths = new HashSet<>();
        private final Set<String> exchangeWrites = new HashSet<>();
        private boolean currentExchangeKnown;

        void prepareExchange(List<ToolExecutionRequest> calls, Map<String, ToolExecutionResultMessage> receipts) {
            currentExchangeKnown = true;
            exchangeWrites.clear();
            for (var call : calls) {
                if (DESKTOP_READ_ONLY_TOOLS.contains(call.name()) && receipts.containsKey(call.id())) continue;
                if (isDesktopWriteTool(call)) {
                    String path = desktopPath(desktopArguments(call));
                    var receipt = receipts.get(call.id());
                    if (path != null && receipt != null && receipt.attributes().containsKey(DESKTOP_CALL_ID)) {
                        exchangeWrites.add(path);
                        continue;
                    }
                }
                // 正序越过未知操作可能已改动工作区。更早、更独立的读写仍可互相验证。
                currentExchangeKnown = false;
                reads.clear();
                knownPaths.clear();
                return;
            }
        }

        DesktopFileRead forPath(String path) {
            return reads.get(path);
        }

        void finishExchange(List<ToolExecutionRequest> calls, Map<String, ToolExecutionResultMessage> receipts) {
            if (!currentExchangeKnown) return;
            knownPaths.addAll(exchangeWrites);
            exchangeWrites.forEach(reads::remove);
            for (var call : calls) {
                if (!"sys_read".equals(call.name())) continue;
                var args = desktopArguments(call);
                String path = desktopPath(args);
                if (path == null || !knownPaths.add(path)) continue;
                var receipt = receipts.get(call.id());
                String content = verifiedFullRead(args, receipt);
                if (content != null) {
                    reads.put(path, new DesktopFileRead(call.id(), content));
                }
            }
        }
    }

    private void projectDuplicateDesktopReceiptArguments(List<ChatMessage> messages) {
        for (int index = 0; index < messages.size(); index++) {
            if (!(messages.get(index) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) continue;
            var calls = ai.toolExecutionRequests();
            if (completeReceipts(messages, index, calls).isEmpty()) continue;
            var byId = new HashMap<String, ToolExecutionRequest>();
            calls.forEach(call -> byId.put(call.id(), call));
            for (int offset = 1; offset <= calls.size(); offset++) {
                var receipt = (ToolExecutionResultMessage) messages.get(index + offset);
                String projection = withoutDuplicateArguments(byId.get(receipt.id()), receipt);
                if (projection != null) {
                    messages.set(index + offset, receipt.toBuilder().contents(TextContent.from(projection)).build());
                }
            }
        }
    }

    private String withoutDuplicateArguments(ToolExecutionRequest call, ToolExecutionResultMessage receipt) {
        int bodyStart = completeDesktopReceiptBodyStart(receipt);
        if (bodyStart < 0 || call.arguments() == null) {
            return null;
        }
        String text = receipt.text();
        int lineBreak = text.indexOf('\n');
        // 在其他投影之后核对，确保当前消息仍保留这些参数与完整回执。
        try {
            var summary = ARGUMENTS_JSON.readTree(text.substring(TOOL_ARGUMENT_PREFIX.length(), lineBreak));
            var arguments = ARGUMENTS_JSON.readTree(call.arguments());
            if (!(summary instanceof ObjectNode fields) || fields.isEmpty() || !(arguments instanceof ObjectNode args)
                    || !fields.properties().stream().allMatch(field -> field.getValue().isTextual()
                            && field.getValue().equals(args.get(field.getKey())))) {
                return null;
            }
            var envelope = ARGUMENTS_JSON.readTree(text.substring(bodyStart));
            if (matchingDesktopEnvelope(envelope, receipt)) {
                String original = text.substring(lineBreak + 1);
                String shorterRead = compactDesktopRead(call, receipt, args, envelope);
                return shorterRead != null && shorterRead.length() < original.length() ? shorterRead : original;
            }
        } catch (JsonProcessingException ignored) {
            // 缺失、已遮蔽或恢复后的非标准回执保留原文。
        }
        return null;
    }

    private String compactDesktopRead(ToolExecutionRequest call, ToolExecutionResultMessage receipt,
                                      ObjectNode args, JsonNode envelope) {
        if (!"sys_read".equals(call.name()) || receipt.isError() || desktopPath(args) == null
                || envelope.hasNonNull("errorMessage") || envelope.hasNonNull("controlAction")
                || args.properties().stream().anyMatch(field -> !DESKTOP_READ_ARGUMENTS.contains(field.getKey()))) {
            return null;
        }
        JsonNode start = args.path("startLine");
        JsonNode end = args.path("endLine");
        if (!validReadBound(start) || !validReadBound(end) || !start.isMissingNode() && !end.isMissingNode()
                && end.intValue() < start.intValue()) return null;
        String source = envelope.path("result").textValue();
        if (!matchesDesktopReadEvidence(args, receipt, source)) return null;
        // 仅去掉工具角色已携带的封套字段，保留内容的 JSON 字符串边界。
        return TOOL_RESULT_PREFIX + args.deepCopy().put("result", source);
    }

    private boolean validReadBound(JsonNode bound) {
        return bound.isMissingNode() || bound.isIntegralNumber() && bound.canConvertToInt() && bound.intValue() > 0;
    }

    private int completeDesktopReceiptBodyStart(ToolExecutionResultMessage receipt) {
        if (!receipt.hasSingleText() || receipt.text() == null || receipt.isError() == null
                || receipt.id() == null || !SAFE_TOOL_CALL_ID.matcher(receipt.id()).matches()
                || !(receipt.attributes().get(DESKTOP_CALL_ID) instanceof String brokerId)
                || !DESKTOP_RECEIPT_ID.matcher(brokerId).matches()
                || !Boolean.FALSE.equals(receipt.attributes().get("compressionApplied"))) {
            return -1;
        }
        String text = receipt.text();
        int lineBreak = text.indexOf('\n');
        if (!text.startsWith(TOOL_ARGUMENT_PREFIX) || lineBreak <= TOOL_ARGUMENT_PREFIX.length()
                || !text.startsWith(TOOL_RESULT_PREFIX, lineBreak + 1)) {
            return -1;
        }
        int bodyStart = lineBreak + 1 + TOOL_RESULT_PREFIX.length();
        Integer bodyLength = text.length() - bodyStart;
        return bodyLength.equals(receipt.attributes().get("rawChars"))
                && bodyLength.equals(receipt.attributes().get("compressedChars")) ? bodyStart : -1;
    }

    private boolean matchingDesktopEnvelope(JsonNode envelope, ToolExecutionResultMessage receipt) {
        return envelope != null && envelope.isObject()
                && receipt.id().equals(envelope.path("callId").textValue())
                && receipt.toolName().equals(ToolNameNormalizer.normalize(envelope.path("toolCode").textValue()))
                && envelope.path("success").isBoolean() && envelope.path("success").booleanValue() != receipt.isError()
                && envelope.has("result");
    }

    long estimateInput(AiModelConfig config, List<ChatMessage> messages, List<ToolSpecification> tools) {
        return estimator.estimateMessages(config, messages) + estimateToolSchemas(config, tools);
    }

    long estimateToolSchemas(AiModelConfig config, List<ToolSpecification> tools) {
        long tokens = 0;
        if (tools != null) {
            for (ToolSpecification tool : tools) {
                tokens += TOOL_SCHEMA_OVERHEAD + estimator.estimateText(config, tool.name())
                        + estimator.estimateText(config, tool.description())
                        + estimator.estimateObjectTokens(config, tool.parameters());
            }
        }
        return tokens;
    }

    private int discardOldestTurn(AiModelConfig config, List<ChatMessage> messages, List<String> discardedContext) {
        int begin = 0;
        while (begin < messages.size() && messages.get(begin) instanceof SystemMessage) {
            begin++;
        }
        for (int index = begin + 1; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage) {
                // 只在用户轮次边界淘汰整段。AI 工具请求和结果留在同一组。
                var oldestTurn = messages.subList(begin, index);
                int removed = oldestTurn.stream().mapToInt(message -> estimator.estimateMessage(config, message)).sum();
                for (ChatMessage message : oldestTurn) {
                    if (discardedContext.size() >= 6) break;
                    if (!(message instanceof UserMessage || message instanceof AiMessage)) continue;
                    String text = ChatMessageText.textOf(message);
                    if (text.isBlank()) continue;
                    int end = text.offsetByCodePoints(0, Math.min(160, text.codePointCount(0, text.length())));
                    discardedContext.add((message instanceof UserMessage ? "用户 " : "助手 ") + text.substring(0, end));
                }
                oldestTurn.clear();
                return removed;
            }
        }
        return 0;
    }

    private boolean containsMedia(ChatMessage message) {
        return switch (message) {
            case UserMessage user -> user.contents().stream().anyMatch(content -> !(content instanceof TextContent));
            case ToolExecutionResultMessage tool -> tool.contents().stream().anyMatch(content -> !(content instanceof TextContent));
            default -> false;
        };
    }

    static final class IncompatibleCandidateException extends RuntimeException {
        private final boolean tokenBudgetLimited;

        IncompatibleCandidateException(String message) {
            this(message, false);
        }

        IncompatibleCandidateException(String message, boolean tokenBudgetLimited) {
            super(message);
            this.tokenBudgetLimited = tokenBudgetLimited;
        }

        boolean isTokenBudgetLimited() {
            return tokenBudgetLimited;
        }
    }
}
