package com.schemaplexai.service.agent.execution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.service.ai.AiModelConfig;
import com.schemaplexai.service.ai.ChatMessageText;
import com.schemaplexai.service.memory.ArchivedChatMemory;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_CALL_ID;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_MAVEN_MODEL_SUMMARY;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_READ_EVIDENCE_HASH;

/**
 * ChatMemory 压缩器（增强版：支持模型驱动摘要）
 *
 * <p>策略：
 * <ul>
 *   <li>底层窗口切换为 TokenWindow</li>
 *   <li>保留最近原文消息</li>
 *   <li>将更早轮次折叠为结构化历史摘要</li>
 *   <li>优先使用小模型生成高质量摘要，降级到字符串提取</li>
 * </ul>
 */
@Slf4j
final class AgentChatMemoryCompactor {

    private static final String SUMMARY_HEADER = "[历史摘要]";
    private static final String TOOL_SUMMARY_HEADER = "[历史工具摘要]";
    private static final String DESKTOP_EVIDENCE_HEADER = "[历史桌面工具证据]";
    private static final String DESKTOP_EVIDENCE_ATTRIBUTE = "schemaplexai.desktopEvidenceSummary";
    private static final String DESKTOP_SHELL_EVIDENCE_PREFIX = "桌面 Shell 回执 ";
    private static final String DESKTOP_MAVEN_FAILURE_PREFIX = "桌面 Maven 失败 ";
    private static final String DESKTOP_FILE_EVIDENCE_PREFIX = "桌面文件进度 ";
    private static final String FILE_READ = "sys_read";
    private static final String FILE_EDIT = "sys_edit";
    private static final String FILE_WRITE = "sys_write";
    private static final int MIN_SUMMARIZE_MESSAGE_COUNT = 8;
    private static final int SUMMARY_CHAR_LIMIT = 1_500;
    private static final int TOOL_SUMMARY_CHAR_LIMIT = 1_200;
    private static final int MAVEN_DIAGNOSTIC_HISTORY_CHAR_LIMIT = 1_050;
    private static final int DESKTOP_SHELL_SUMMARY_CHAR_LIMIT = 2_400;
    private static final int DESKTOP_SHELL_RESULT_CHAR_LIMIT = 1_024;
    private static final int DESKTOP_SHELL_EXCERPT_CHAR_LIMIT = 80;
    private static final int DESKTOP_FILE_SUMMARY_CHAR_LIMIT = 1_600;
    private static final int DESKTOP_FILE_RECEIPT_CHAR_LIMIT = 12_000;
    private static final Pattern DESKTOP_BROKER_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final ObjectMapper RECEIPT_JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int LARGE_TOOL_RESULT_CHAR_THRESHOLD = 1_200;

    private static final String MODEL_SUMMARIZATION_PROMPT = """
            将以下对话历史压缩为简洁的结构化摘要。保留：
            1. 用户的核心目标和需求
            2. 已完成的关键操作和结果
            3. 重要的技术决策和约束
            4. 待处理的事项

            输出格式：
            目标: <一句话概括>
            已完成: <关键操作列表>
            决策: <重要决策>
            待处理: <未完成事项>

            保持简洁，总长度不超过800字。
            """;

    private final TokenEstimatorSupport tokenEstimatorSupport;
    private final ChatModel compactionModel;
    private final String initialSystemPrompt;

    AgentChatMemoryCompactor(TokenEstimatorSupport tokenEstimatorSupport) {
        this(tokenEstimatorSupport, null);
    }

    /**
     * @param compactionModel 用于上下文压缩的小模型（可选，null 时降级到字符串提取）；
     *                        执行级注入，实例无共享可变状态，并发安全
     */
    AgentChatMemoryCompactor(TokenEstimatorSupport tokenEstimatorSupport, ChatModel compactionModel) {
        this(tokenEstimatorSupport, compactionModel, "");
    }

    AgentChatMemoryCompactor(TokenEstimatorSupport tokenEstimatorSupport, ChatModel compactionModel, String systemPrompt) {
        this.tokenEstimatorSupport = tokenEstimatorSupport;
        this.compactionModel = compactionModel;
        this.initialSystemPrompt = systemPrompt;
    }

    ArchivedChatMemory createChatMemory(String conversationId, String executionId,
                                         AgentEngineParams params, AiModelConfig modelConfig,
                                         CompositeChatMemoryStore chatMemoryStore) {
        return createChatMemory(conversationId, executionId, params, modelConfig, chatMemoryStore, false);
    }

    ArchivedChatMemory createChatMemory(String conversationId, String executionId,
                                         AgentEngineParams params, AiModelConfig modelConfig,
                                         CompositeChatMemoryStore chatMemoryStore, boolean currentExecutionOnly) {
        int memoryBudgetTokens = resolveMemoryBudgetTokens(params, modelConfig);
        ChatMemory window = TokenWindowChatMemory.builder()
                .id(conversationId)
                .maxTokens(memoryBudgetTokens, tokenEstimatorSupport.resolveEstimator(modelConfig))
                .build();
        // 回放条数用于限制查询成本。扩大普通窗口，减少条数先于 Token 窗口淘汰短消息。
        int replayLimit = currentExecutionOnly ? params.getMaxMessages()
                : Math.max(params.getMaxMessages(), memoryBudgetTokens / 64);
        return new ArchivedChatMemory(conversationId, executionId, chatMemoryStore, window,
                Math.clamp(replayLimit, 8, 2_000), currentExecutionOnly);
    }

    CompactionResult compactIfNeeded(ChatMemory chatMemory, AgentEngineParams params, AiModelConfig modelConfig) {
        return compactIfNeeded(chatMemory, params, modelConfig,
                compactionModel == null ? null : request -> compactionModel.chat(request.messages()));
    }

    CompactionResult compactIfNeeded(ChatMemory chatMemory, AgentEngineParams params, AiModelConfig modelConfig,
                                     SummaryModel summaryModel) {
        return compactIfNeeded(chatMemory, params, modelConfig, false, summaryModel);
    }

    CompactionResult compactIfNeeded(ChatMemory chatMemory, AgentEngineParams params, AiModelConfig modelConfig,
                                     boolean approvalResume, SummaryModel summaryModel) {
        return compactIfNeeded(chatMemory, params, modelConfig, approvalResume, false, null, summaryModel);
    }

    CompactionResult compactIfNeeded(ChatMemory chatMemory, AgentEngineParams params, AiModelConfig modelConfig,
                                     boolean approvalResume, boolean verifiedDesktop, UserMessage exactDesktopTask,
                                     SummaryModel summaryModel) {
        return compactIfNeeded(chatMemory, params, modelConfig, approvalResume, verifiedDesktop, exactDesktopTask,
                summaryModel, Long.MAX_VALUE);
    }

    CompactionResult compactIfNeeded(ChatMemory chatMemory, AgentEngineParams params, AiModelConfig modelConfig,
                                     boolean approvalResume, boolean verifiedDesktop, UserMessage exactDesktopTask,
                                     SummaryModel summaryModel, long remainingExecutionTokens) {
        return compactIfNeeded(chatMemory, params, modelConfig, approvalResume, verifiedDesktop, exactDesktopTask,
                summaryModel, remainingExecutionTokens, promptTokens(modelConfig));
    }

    CompactionResult compactIfNeeded(ChatMemory chatMemory, AgentEngineParams params, AiModelConfig modelConfig,
                                     boolean approvalResume, boolean verifiedDesktop, UserMessage exactDesktopTask,
                                     SummaryModel summaryModel, long remainingExecutionTokens, long requestOverheadTokens) {
        if (chatMemory == null || chatMemory.messages() == null || chatMemory.messages().isEmpty()) {
            return CompactionResult.noop();
        }
        List<ChatMessage> messages = new ArrayList<>(chatMemory.messages());
        int beforeTokens = tokenEstimatorSupport.estimateMessages(modelConfig, messages);
        int memoryBudgetTokens = resolveMemoryBudgetTokens(modelConfig, requestOverheadTokens);
        // 审批恢复重放原始轨迹，预算内保留多笔工具审批积累的证据。
        if (approvalResume && beforeTokens < memoryBudgetTokens) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }
        int recentRawMessageCount = resolveRecentRawMessageCount(params);
        boolean tokenBudgetTight = beforeTokens >= (int) (memoryBudgetTokens * 0.58);
        boolean earlyDesktopCompaction = verifiedDesktop && !tokenBudgetTight
                && remainingExecutionTokens > 0 && remainingExecutionTokens < memoryBudgetTokens * 4L
                && beforeTokens >= Math.min((int) (memoryBudgetTokens * 0.40), remainingExecutionTokens * 0.80)
                && canCompactDesktopHistoryEarly(messages, exactDesktopTask);

        int splitIndex = resolveSafeSplitIndex(messages, recentRawMessageCount);
        if (verifiedDesktop) {
            // 给本机编码连续的读取与构建批次留出原始工作上下文。
            if (!tokenBudgetTight && !earlyDesktopCompaction) {
                return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
            }
            splitIndex = Math.min(splitIndex, recentDesktopToolExchangeStart(messages));
        }
        if (splitIndex >= messages.size() - 2) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }

        List<ChatMessage> historical = messages.subList(0, splitIndex);
        List<ChatMessage> recent = messages.subList(splitIndex, messages.size());
        // 预算充足时，近期保护段里的大回执继续保留原文。
        boolean containsLargeToolResult = containsLargeToolResult(tokenBudgetTight ? messages : historical);
        if (!containsLargeToolResult
                && (!(tokenBudgetTight || earlyDesktopCompaction) || messages.size() < MIN_SUMMARIZE_MESSAGE_COUNT)) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }

        boolean preserveExactTask = verifiedDesktop && exactDesktopTask != null
                && historical.contains(exactDesktopTask) && !recent.contains(exactDesktopTask);
        // 桌面验收依赖工具回执，按回执提取历史证据。
        String summary = verifiedDesktop ? buildDesktopEvidenceSummary(historical, exactDesktopTask)
                : modelBasedSummarize(historical, summaryModel, params);
        if (!StringUtils.hasText(summary)) {
            summary = buildHistoricalSummary(historical);
        }
        if (!StringUtils.hasText(summary)) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }

        List<ChatMessage> compacted = new ArrayList<>();
        compacted.add(verifiedDesktop
                ? UserMessage.builder().contents(List.of(TextContent.from(summary)))
                        .attributes(Map.of(DESKTOP_EVIDENCE_ATTRIBUTE, true)).build()
                : UserMessage.from(summary));
        if (preserveExactTask) {
            compacted.add(exactDesktopTask);
        }
        compacted.addAll(recent);

        int afterTokens = tokenEstimatorSupport.estimateMessages(modelConfig, compacted);
        if (verifiedDesktop && afterTokens >= beforeTokens) {
            return new CompactionResult(false, beforeTokens, beforeTokens, 0, messages.size(), messages.size());
        }
        chatMemory.set(compacted);
        return new CompactionResult(true, beforeTokens, afterTokens, summary.length(), messages.size(), compacted.size());
    }

    private boolean canCompactDesktopHistoryEarly(List<ChatMessage> messages, UserMessage exactDesktopTask) {
        if (exactDesktopTask == null || !exactDesktopTask.hasSingleText() || !messages.contains(exactDesktopTask)) {
            return false;
        }
        int exchanges = 0;
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            if ((message instanceof UserMessage user && !user.hasSingleText())
                    || (message instanceof ToolExecutionResultMessage tool && !tool.hasSingleText())) {
                return false;
            }
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                var exchange = collectToolExchangeSegment(messages, index, ai);
                if (!exchange.complete() || exchange.resultMessages().size() != ai.toolExecutionRequests().size()) {
                    return false;
                }
                exchanges++;
            }
        }
        return exchanges >= 4;
    }

    private String buildDesktopEvidenceSummary(List<ChatMessage> historical, UserMessage exactDesktopTask) {
        Set<String> evidence = new LinkedHashSet<>();
        Set<String> shellEvidenceLines = new LinkedHashSet<>();
        Set<String> buildFailures = new LinkedHashSet<>();
        var fileProgress = new LinkedHashMap<String, FileProgress>();
        var verifiedEvidence = verifiedDesktopEvidence(historical);
        for (ChatMessage message : historical.reversed()) {
            if (message instanceof ToolExecutionResultMessage receipt) {
                DesktopEvidence verified = verifiedEvidence.get(receipt);
                if (verified instanceof ShellEvidence shell) {
                    shellEvidenceLines.add(shell.display());
                    continue;
                }
                if (verified instanceof FileEvidence file) {
                    recordFileProgress(fileProgress, file);
                    continue;
                }
                Object mavenSummary = receipt.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY);
                if (Boolean.TRUE.equals(receipt.isError())
                        && AgentModelRequestPlanner.desktopMavenProjection(receipt) != null) {
                    buildFailures.add(DESKTOP_MAVEN_FAILURE_PREFIX
                            + compactText((String) mavenSummary, MAVEN_DIAGNOSTIC_HISTORY_CHAR_LIMIT));
                    continue;
                }
                String text = mavenSummary instanceof String maven && Boolean.FALSE.equals(receipt.isError())
                        ? maven : ChatMessageText.textOf(receipt);
                String excerpt = compactText(text, 180);
                if (StringUtils.hasText(excerpt)) {
                    String outcome = Boolean.TRUE.equals(receipt.isError()) ? "失败"
                            : Boolean.FALSE.equals(receipt.isError()) ? "成功" : "结果";
                    evidence.add("工具[" + receipt.toolName() + "] " + outcome + " " + excerpt);
                }
            } else if (message instanceof UserMessage user && !user.equals(exactDesktopTask) && user.hasSingleText()) {
                String text = user.singleText();
                if (text.startsWith(DESKTOP_EVIDENCE_HEADER)
                        && Boolean.TRUE.equals(user.attributes().get(DESKTOP_EVIDENCE_ATTRIBUTE))) {
                    for (String line : text.lines().skip(1).filter(entry -> entry.startsWith("- "))
                            .map(entry -> entry.substring(2)).toList()) {
                        if (line.startsWith(DESKTOP_MAVEN_FAILURE_PREFIX)) {
                            buildFailures.add(line);
                        } else if (line.startsWith(DESKTOP_FILE_EVIDENCE_PREFIX)) {
                            restoreFileProgress(fileProgress, line);
                        } else {
                            (line.startsWith(DESKTOP_SHELL_EVIDENCE_PREFIX) ? shellEvidenceLines : evidence).add(line);
                        }
                    }
                } else if (StringUtils.hasText(text)) {
                    evidence.add("历史用户输入 " + compactText(text, 180));
                }
            }
        }
        StringBuilder summary = new StringBuilder(DESKTOP_EVIDENCE_HEADER).append("\n");
        int headerLength = summary.length();
        for (var entry : fileProgress.entrySet()) {
            var json = RECEIPT_JSON.createObjectNode().put("path", entry.getKey());
            JsonNode read = entry.getValue().read;
            JsonNode change = entry.getValue().change;
            boolean newerChange = entry.getValue().changeAfterRead;
            if (read != null) json.set("read", read);
            if (change != null) json.set("change", change);
            if (newerChange) json.put("changeAfterRead", true);
            String line = DESKTOP_FILE_EVIDENCE_PREFIX + json;
            if (summary.length() + line.length() + 3 > DESKTOP_FILE_SUMMARY_CHAR_LIMIT) break;
            summary.append("- ").append(line).append("\n");
        }
        int fileBudgetUsed = summary.length() - headerLength;
        int remainingEvidenceLimit = fileBudgetUsed + (shellEvidenceLines.isEmpty()
                ? TOOL_SUMMARY_CHAR_LIMIT : DESKTOP_SHELL_SUMMARY_CHAR_LIMIT);
        buildFailures.stream().findFirst().ifPresent(line -> appendEvidence(summary, Set.of(line), remainingEvidenceLimit));
        appendEvidence(summary, shellEvidenceLines, remainingEvidenceLimit);
        appendEvidence(summary, evidence, remainingEvidenceLimit);
        return summary.length() == headerLength ? null : summary.toString().stripTrailing();
    }

    private void appendEvidence(StringBuilder summary, Set<String> evidence, int charLimit) {
        for (String line : evidence) {
            if (summary.length() + line.length() + 3 > charLimit) {
                break;
            }
            summary.append("- ").append(line).append("\n");
        }
    }

    private Map<ToolExecutionResultMessage, DesktopEvidence> verifiedDesktopEvidence(List<ChatMessage> historical) {
        Map<ToolExecutionResultMessage, DesktopEvidence> evidence = new HashMap<>();
        Map<String, ToolExecutionResultMessage> accepted = new HashMap<>();
        Set<String> seenCallIds = new HashSet<>();
        for (int index = 0; index < historical.size(); index++) {
            if (!(historical.get(index) instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) continue;
            var exchange = collectToolExchangeSegment(historical, index, ai);
            if (!exchange.complete() || exchange.resultMessages().size() != ai.toolExecutionRequests().size()) continue;
            for (var call : ai.toolExecutionRequests()) {
                if (!StringUtils.hasText(call.id()) || !StringUtils.hasText(call.name())) continue;
                if (!seenCallIds.add(call.id())) {
                    evidence.remove(accepted.remove(call.id()));
                    continue;
                }
                for (var receipt : exchange.resultMessages()) {
                    if (!call.id().equals(receipt.id())) continue;
                    DesktopEvidence note = switch (call.name()) {
                        case "sys_bash" -> {
                            String shell = summarizeDesktopShell(call, receipt);
                            yield shell == null ? null : new ShellEvidence(shell);
                        }
                        case FILE_READ, FILE_EDIT, FILE_WRITE -> summarizeDesktopFile(call, receipt);
                        default -> null;
                    };
                    if (note != null) {
                        evidence.put(receipt, note);
                        accepted.put(call.id(), receipt);
                    }
                    break;
                }
            }
        }
        return evidence;
    }

    private String summarizeDesktopShell(ToolExecutionRequest call, ToolExecutionResultMessage receipt) {
        var parsed = parseDesktopReceipt(call, receipt, DESKTOP_SHELL_RESULT_CHAR_LIMIT);
        if (parsed == null || receipt.attributes().containsKey(DESKTOP_MAVEN_MODEL_SUMMARY)) return null;
        try {
            var args = parsed.arguments();
            var command = args.path("command");
            if (!command.isTextual() || !command.equals(parsed.displayed().path("command"))
                    || command.textValue().length() > 160) return null;
            var shell = RECEIPT_JSON.readTree(parsed.result());
            if (shell == null || !shell.path("exitCode").isIntegralNumber() || !shell.path("exitCode").canConvertToInt()
                    || (shell.path("exitCode").intValue() == 0) == receipt.isError()
                    || !shell.path("stdout").isTextual() || !shell.path("stderr").isTextual()) return null;
            String workdir = desktopWorkdir(args);
            if (workdir == null) return null;
            var summary = RECEIPT_JSON.createObjectNode().put("command", command.textValue())
                    .put("workingDirectory", workdir)
                    .put("exitCode", shell.path("exitCode").intValue())
                    .put("stdout", shellExcerpt(shell.path("stdout").textValue()))
                    .put("stderr", shellExcerpt(shell.path("stderr").textValue().replaceFirst(
                            "^NOTE: Picked up JDK_JAVA_OPTIONS: -Duser\\.home=[^\\r\\n]*\\R?", "")));
            return DESKTOP_SHELL_EVIDENCE_PREFIX + summary;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private DesktopReceipt parseDesktopReceipt(ToolExecutionRequest call, ToolExecutionResultMessage receipt, int limit) {
        if (!call.name().equals(receipt.toolName()) || !receipt.hasSingleText() || receipt.isError() == null
                || !(receipt.attributes().get(DESKTOP_CALL_ID) instanceof String brokerId)
                || !DESKTOP_BROKER_ID.matcher(brokerId).matches()
                || !Boolean.FALSE.equals(receipt.attributes().get("compressionApplied"))) {
            return null;
        }
        String text = receipt.text();
        if (text == null || !StringUtils.hasText(call.arguments())) return null;
        String prefix = "请求参数: ";
        String resultPrefix = "\n工具结果: ";
        int split = text.indexOf(resultPrefix);
        if (!text.startsWith(prefix) || split < prefix.length()) return null;
        String body = text.substring(split + resultPrefix.length());
        if (body.length() > limit
                || !Integer.valueOf(body.length()).equals(receipt.attributes().get("rawChars"))
                || !Integer.valueOf(body.length()).equals(receipt.attributes().get("compressedChars"))) return null;
        try {
            var args = RECEIPT_JSON.readTree(call.arguments());
            var displayed = RECEIPT_JSON.readTree(text.substring(prefix.length(), split));
            var envelope = RECEIPT_JSON.readTree(body);
            if (args == null || displayed == null || envelope == null || !args.isObject() || !displayed.isObject()
                    || !call.id().equals(envelope.path("callId").textValue())
                    || !call.name().replace('_', '.').equals(envelope.path("toolCode").textValue())
                    || !envelope.path("success").isBoolean() || envelope.path("success").booleanValue() == receipt.isError()
                    || envelope.hasNonNull("controlAction") || !envelope.path("result").isTextual()) return null;
            return new DesktopReceipt(args, displayed, envelope.path("result").textValue(),
                    envelope.hasNonNull("errorMessage"));
        } catch (JsonProcessingException ignored) {
            // 缺少完整结构或证据不一致时沿用普通摘要。
            return null;
        }
    }

    private String desktopWorkdir(JsonNode args) {
        var directory = args.hasNonNull("workingDirectory") ? args.get("workingDirectory") : args.get("workdir");
        if (directory == null || directory.isNull()) return ".";
        return directory.isTextual() && directory.textValue().length() <= 160 ? directory.textValue() : null;
    }

    private FileEvidence summarizeDesktopFile(ToolExecutionRequest call, ToolExecutionResultMessage receipt) {
        var parsed = parseDesktopReceipt(call, receipt, DESKTOP_FILE_RECEIPT_CHAR_LIMIT);
        if (parsed == null || !Boolean.FALSE.equals(receipt.isError()) || parsed.hasError()) return null;
        var pathNode = parsed.arguments().path("path");
        if (!pathNode.isTextual() || !pathNode.equals(parsed.displayed().path("path"))) return null;
        String workdir = desktopWorkdir(parsed.arguments());
        String path = canonicalDesktopPath(workdir, pathNode.textValue());
        if (path == null) return null;
        var note = RECEIPT_JSON.createObjectNode().put("tool", call.name()).put("status", "success");
        if (!FILE_READ.equals(call.name())) {
            if (!parsed.result().equals("已保存 " + pathNode.textValue())) return null;
            return new FileEvidence(path, false, note);
        }
        String signedRead = RECEIPT_JSON.createArrayNode().add(parsed.arguments()).add(parsed.result()).toString();
        if (!HashUtils.sha256Hex(signedRead.getBytes(StandardCharsets.UTF_8))
                .equals(receipt.attributes().get(DESKTOP_READ_EVIDENCE_HASH))) return null;
        if (parsed.arguments().has("startLine") || parsed.arguments().has("endLine")) {
            note.put("contents", "range omitted");
            for (String bound : List.of("startLine", "endLine")) {
                if (parsed.arguments().path(bound).isIntegralNumber()) {
                    note.set(bound, parsed.arguments().get(bound));
                }
            }
            if (parsed.result().endsWith("\n[已截断]")) note.put("truncated", true);
        } else {
            note.put("contents", parsed.result().endsWith("\n[已截断]") ? "truncated" : "omitted");
        }
        note.put("characters", parsed.result().length());
        return new FileEvidence(path, true, note);
    }

    private String canonicalDesktopPath(String workdir, String path) {
        if (workdir == null || workdir.isBlank() || workdir.startsWith("/") || workdir.startsWith("~")
                || path.isBlank() || path.startsWith("/") || path.startsWith("~")) return null;
        var segments = new ArrayList<String>();
        for (String segment : (workdir + "/" + path).split("/")) {
            if (segment.equals("..") || segment.contains("\\") || segment.contains(":")
                    || segment.chars().anyMatch(Character::isISOControl)) return null;
            if (!segment.isEmpty() && !segment.equals(".")) segments.add(segment);
        }
        String resolved = String.join("/", segments);
        return resolved.isEmpty() || resolved.length() > 160 ? null : resolved;
    }

    private void recordFileProgress(Map<String, FileProgress> files, FileEvidence file) {
        var progress = files.computeIfAbsent(file.path(), ignored -> new FileProgress());
        if (file.read()) {
            if (progress.read == null) progress.read = file.details();
        } else if (progress.change == null) {
            progress.change = file.details();
            progress.changeAfterRead = progress.read == null;
        }
    }

    private void restoreFileProgress(Map<String, FileProgress> files, String line) {
        try {
            var entry = RECEIPT_JSON.readTree(line.substring(DESKTOP_FILE_EVIDENCE_PREFIX.length()));
            if (entry == null || !entry.path("path").isTextual()) return;
            String path = entry.path("path").textValue();
            var progress = files.computeIfAbsent(path, ignored -> new FileProgress());
            boolean hadNewerRead = progress.read != null;
            if (!hadNewerRead && entry.path("read").isObject()) progress.read = entry.get("read");
            if (progress.change == null && entry.path("change").isObject()) {
                progress.change = entry.get("change");
                progress.changeAfterRead = !hadNewerRead && entry.path("changeAfterRead").asBoolean(false);
            }
        } catch (JsonProcessingException ignored) {
            // 仅携带本窗口先前生成的完整文件证据。
        }
    }

    private interface DesktopEvidence {}
    private record DesktopReceipt(JsonNode arguments, JsonNode displayed, String result, boolean hasError) {}
    private record ShellEvidence(String display) implements DesktopEvidence {}
    private record FileEvidence(String path, boolean read, JsonNode details) implements DesktopEvidence {}
    private static final class FileProgress {
        private JsonNode read;
        private JsonNode change;
        private boolean changeAfterRead;
    }

    private String shellExcerpt(String text) {
        return text.length() <= DESKTOP_SHELL_EXCERPT_CHAR_LIMIT ? text
                : text.substring(0, DESKTOP_SHELL_EXCERPT_CHAR_LIMIT) + "[已截断]";
    }

    /**
     * 在真正发给模型前清洗消息序列，避免窗口淘汰后残留非法的工具调用/工具结果组合。
     */
    NormalizationResult normalizeForRequest(ChatMemory chatMemory) {
        if (chatMemory == null || chatMemory.messages() == null || chatMemory.messages().isEmpty()) {
            return NormalizationResult.noop();
        }
        List<ChatMessage> original = new ArrayList<>(chatMemory.messages());
        List<ChatMessage> normalized = new ArrayList<>();
        int convertedSegments = 0;
        int droppedMessages = 0;

        for (int index = 0; index < original.size(); ) {
            ChatMessage current = original.get(index);
            if (current instanceof AiMessage aiMessage && isEmptyAiMessage(aiMessage)) {
                droppedMessages++;
                index++;
                continue;
            }
            if (current instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                ToolExchangeSegment segment = collectToolExchangeSegment(original, index, aiMessage);
                if (segment.complete()) {
                    normalized.add(aiMessage);
                    normalized.addAll(segment.resultMessages());
                } else {
                    String summary = buildToolExchangeSummary(aiMessage, segment.resultMessages());
                    if (StringUtils.hasText(summary)) {
                        normalized.add(UserMessage.from(summary));
                        convertedSegments++;
                    } else {
                        droppedMessages += 1 + segment.resultMessages().size();
                    }
                }
                index = segment.nextIndex();
                continue;
            }
            if (current instanceof ToolExecutionResultMessage) {
                List<ToolExecutionResultMessage> orphanResults = new ArrayList<>();
                int cursor = index;
                while (cursor < original.size() && original.get(cursor) instanceof ToolExecutionResultMessage toolMessage) {
                    orphanResults.add(toolMessage);
                    cursor++;
                }
                String summary = buildOrphanToolSummary(orphanResults);
                if (StringUtils.hasText(summary)) {
                    normalized.add(UserMessage.from(summary));
                    convertedSegments++;
                } else {
                    droppedMessages += orphanResults.size();
                }
                index = cursor;
                continue;
            }
            normalized.add(current);
            index++;
        }

        if (convertedSegments == 0 && droppedMessages == 0) {
            return new NormalizationResult(false, 0, 0, original.size(), original.size());
        }
        chatMemory.set(normalized);
        return new NormalizationResult(true, convertedSegments, droppedMessages, original.size(), normalized.size());
    }

    int resolveMemoryBudgetTokens(AgentEngineParams params, AiModelConfig modelConfig) {
        return resolveMemoryBudgetTokens(modelConfig, promptTokens(modelConfig));
    }

    private int promptTokens(AiModelConfig config) {
        return StringUtils.hasText(initialSystemPrompt)
                ? tokenEstimatorSupport.estimateMessage(config, SystemMessage.from(initialSystemPrompt)) : 0;
    }

    int resolveMemoryBudgetTokens(AiModelConfig modelConfig, long requestOverheadTokens) {
        int contextWindowTokens = modelConfig != null ? modelConfig.resolvedContextWindowTokens() : 16_384;
        int outputReserveTokens = modelConfig != null && modelConfig.getMaxTokens() > 0 ? modelConfig.getMaxTokens() : 2_048;
        int target = (int) Math.max(1L, (long) contextWindowTokens - outputReserveTokens - requestOverheadTokens);
        double fraction = modelConfig == null || modelConfig.getContextWindowTokens() <= 0 ? 0.50
                : contextWindowTokens >= 128_000 ? 0.85 : 0.75;
        int ceiling = Math.max(1, (int) (contextWindowTokens * fraction));
        return Math.min(target, ceiling);
    }

    private int resolveRecentRawMessageCount(AgentEngineParams params) {
        if (params == null) {
            return 5;
        }
        return Math.max(4, Math.min(6, (params.getMaxToolCallsPerRound() / 2) + 1));
    }

    private boolean containsLargeToolResult(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (ChatMessage message : messages) {
            if (message instanceof ToolExecutionResultMessage toolMessage
                    && ChatMessageText.textOf(toolMessage).length() >= LARGE_TOOL_RESULT_CHAR_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    private int resolveSafeSplitIndex(List<ChatMessage> messages, int recentRawMessageCount) {
        int splitIndex = Math.max(2, messages.size() - recentRawMessageCount);
        while (splitIndex > 0 && splitIndex < messages.size()
                && messages.get(splitIndex) instanceof ToolExecutionResultMessage) {
            splitIndex--;
        }
        if (splitIndex <= 0) {
            return messages.size();
        }
        return splitIndex;
    }

    private int recentDesktopToolExchangeStart(List<ChatMessage> messages) {
        int toolExchanges = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (messages.get(index) instanceof AiMessage ai && ai.hasToolExecutionRequests() && ++toolExchanges == 2) {
                return index;
            }
        }
        return messages.size();
    }

    private ToolExchangeSegment collectToolExchangeSegment(List<ChatMessage> messages, int aiIndex, AiMessage aiMessage) {
        List<ToolExecutionResultMessage> resultMessages = new ArrayList<>();
        int cursor = aiIndex + 1;
        while (cursor < messages.size() && messages.get(cursor) instanceof ToolExecutionResultMessage toolMessage) {
            resultMessages.add(toolMessage);
            cursor++;
        }
        return new ToolExchangeSegment(resultMessages, cursor, isCompleteToolExchange(aiMessage, resultMessages));
    }

    private boolean isCompleteToolExchange(AiMessage aiMessage, List<ToolExecutionResultMessage> resultMessages) {
        List<ToolExecutionRequest> toolRequests = aiMessage.toolExecutionRequests();
        if (toolRequests == null || toolRequests.isEmpty()) {
            return true;
        }
        if (resultMessages.isEmpty()) {
            return false;
        }
        Set<String> requestIds = toolRequests.stream()
                .map(ToolExecutionRequest::id)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (requestIds.isEmpty()) {
            return resultMessages.size() >= toolRequests.size();
        }
        Set<String> resultIds = resultMessages.stream()
                .map(ToolExecutionResultMessage::id)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return requestIds.equals(resultIds);
    }

    private boolean isEmptyAiMessage(AiMessage aiMessage) {
        if (aiMessage == null) {
            return true;
        }
        if (aiMessage.hasToolExecutionRequests()) {
            return false;
        }
        return !StringUtils.hasText(aiMessage.text());
    }

    private String buildToolExchangeSummary(AiMessage aiMessage, List<ToolExecutionResultMessage> resultMessages) {
        List<String> lines = new ArrayList<>();
        if (StringUtils.hasText(aiMessage.text())) {
            lines.add("模型规划: " + compactText(aiMessage.text(), 180));
        }
        String plannedTools = summarizeToolRequests(aiMessage.toolExecutionRequests());
        if (StringUtils.hasText(plannedTools)) {
            lines.add("计划调用: " + plannedTools);
        }
        appendToolResultLines(lines, resultMessages);
        return buildToolSummary(lines);
    }

    private String buildOrphanToolSummary(List<ToolExecutionResultMessage> resultMessages) {
        List<String> lines = new ArrayList<>();
        if (resultMessages == null || resultMessages.isEmpty()) {
            return null;
        }
        lines.add("检测到历史工具结果与原始工具调用已失配，以下结果转为纯文本证据继续参与推理。");
        appendToolResultLines(lines, resultMessages);
        return buildToolSummary(lines);
    }

    private String summarizeToolRequests(List<ToolExecutionRequest> toolRequests) {
        if (toolRequests == null || toolRequests.isEmpty()) {
            return null;
        }
        return toolRequests.stream()
                .limit(4)
                .map(request -> {
                    String toolName = StringUtils.hasText(request.name()) ? request.name() : "unknown";
                    String arguments = compactText(request.arguments(), 80);
                    return StringUtils.hasText(arguments) ? toolName + "(" + arguments + ")" : toolName;
                })
                .collect(Collectors.joining("；"));
    }

    private void appendToolResultLines(List<String> lines, List<ToolExecutionResultMessage> resultMessages) {
        if (resultMessages == null || resultMessages.isEmpty()) {
            return;
        }
        for (ToolExecutionResultMessage resultMessage : resultMessages) {
            String text = compactText(ChatMessageText.textOf(resultMessage), 220);
            if (!StringUtils.hasText(text)) {
                continue;
            }
            String toolName = StringUtils.hasText(resultMessage.toolName()) ? resultMessage.toolName() : "unknown";
            lines.add("工具[" + toolName + "] " + text);
            if (lines.size() >= 6) {
                break;
            }
        }
    }

    private String buildToolSummary(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        StringBuilder summary = new StringBuilder(TOOL_SUMMARY_HEADER).append("\n");
        for (String line : lines) {
            summary.append("- ").append(line).append("\n");
        }
        return compactText(summary.toString(), TOOL_SUMMARY_CHAR_LIMIT);
    }

    /**
     * 模型驱动摘要：使用小模型生成高质量压缩摘要
     */
    private String modelBasedSummarize(List<ChatMessage> historicalMessages, SummaryModel summaryModel,
                                       AgentEngineParams params) {
        if (summaryModel == null || historicalMessages == null || historicalMessages.isEmpty()) {
            return null;
        }
        String failureReason = "empty_response";
        try {
            StringBuilder historyText = new StringBuilder();
            for (ChatMessage msg : historicalMessages) {
                String role = msg instanceof UserMessage ? "用户" :
                        msg instanceof AiMessage ? "AI" : "系统";
                String text = ChatMessageText.textOf(msg);
                if (StringUtils.hasText(text)) {
                    historyText.append(role).append(": ").append(compactText(text, 300)).append("\n");
                }
            }
            if (historyText.isEmpty()) {
                return null;
            }
            Metrics.counter("agent.memory.compaction.attempt").increment();
            ChatResponse response = summaryModel.chat(ChatRequest.builder().messages(List.of(
                    SystemMessage.from(params != null && StringUtils.hasText(params.getCompactionPrompt())
                            ? params.getCompactionPrompt() : MODEL_SUMMARIZATION_PROMPT),
                    UserMessage.from(historyText.toString())
            )).build());
            String summary = response.aiMessage().text();
            if (StringUtils.hasText(summary)) {
                log.info("模型驱动上下文压缩完成: inputMessages={}, summaryChars={}",
                        historicalMessages.size(), summary.length());
                return SUMMARY_HEADER + "\n" + compactText(summary, SUMMARY_CHAR_LIMIT);
            }
        } catch (CancellationException e) {
            throw e;
        } catch (Exception e) {
            failureReason = "model_failed";
            log.warn("模型驱动摘要失败，降级到规则提取，异常类型: {}", e.getClass().getSimpleName());
        }
        Metrics.counter("agent.memory.compaction.fallback", "reason", failureReason).increment();
        return null;
    }

    @FunctionalInterface
    interface SummaryModel {
        ChatResponse chat(ChatRequest request) throws Exception;
    }

    private String buildHistoricalSummary(List<ChatMessage> historicalMessages) {
        List<String> goals = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        List<String> references = new ArrayList<>();

        for (ChatMessage message : historicalMessages) {
            if (message instanceof UserMessage) {
                String text = compactText(ChatMessageText.textOf(message), 180);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (text.startsWith(SUMMARY_HEADER)) {
                    facts.add(compactText(text.replace(SUMMARY_HEADER, ""), 220));
                } else if (goals.size() < 2) {
                    goals.add(text);
                }
            } else if (message instanceof ToolExecutionResultMessage toolMessage) {
                String text = compactText(ChatMessageText.textOf(toolMessage), 180);
                if (StringUtils.hasText(text) && references.size() < 4) {
                    references.add("工具[" + toolMessage.toolName() + "] " + text);
                }
            } else if (message instanceof AiMessage aiMessage) {
                String text = compactText(aiMessage.text(), 180);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                if (text.startsWith(SUMMARY_HEADER)) {
                    facts.add(compactText(text.replace(SUMMARY_HEADER, ""), 220));
                } else if (decisions.size() < 4) {
                    decisions.add(text);
                } else if (facts.size() < 4) {
                    facts.add(text);
                }
            }
        }

        StringBuilder summary = new StringBuilder(SUMMARY_HEADER).append("\n");
        appendSection(summary, "目标", goals, "延续当前用户任务并完成节点目标");
        appendSection(summary, "关键事实", facts, "已基于历史轮次收集到部分有效证据");
        appendSection(summary, "关键决定", decisions, "已形成阶段性方案，保留最近原文消息继续推理");
        appendSection(summary, "引用", references, "详细原始证据保留在最近消息窗口内");
        return compactText(summary.toString(), SUMMARY_CHAR_LIMIT);
    }

    private void appendSection(StringBuilder summary, String title, List<String> items, String defaultText) {
        summary.append(title).append(":\n");
        if (items.isEmpty()) {
            summary.append("- ").append(defaultText).append("\n");
            return;
        }
        for (String item : items) {
            summary.append("- ").append(item).append("\n");
        }
    }

    private String compactText(String text, int limit) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        if (normalized.length() <= limit) {
            return normalized;
        }
        return normalized.substring(0, Math.max(limit, 1)) + "...";
    }

    record CompactionResult(boolean compacted,
                            int beforeTokens,
                            int afterTokens,
                            int summaryChars,
                            int beforeMessages,
                            int afterMessages) {

        static CompactionResult noop() {
            return new CompactionResult(false, 0, 0, 0, 0, 0);
        }
    }

    record NormalizationResult(boolean normalized,
                               int convertedSegments,
                               int droppedMessages,
                               int beforeMessages,
                               int afterMessages) {

        static NormalizationResult noop() {
            return new NormalizationResult(false, 0, 0, 0, 0);
        }
    }

    private record ToolExchangeSegment(List<ToolExecutionResultMessage> resultMessages,
                                       int nextIndex,
                                       boolean complete) {
    }
}
