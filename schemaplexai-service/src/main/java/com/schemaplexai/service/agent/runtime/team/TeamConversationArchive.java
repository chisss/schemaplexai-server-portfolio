package com.schemaplexai.service.agent.runtime.team;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.ai.ChatMessageText;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import com.schemaplexai.service.memory.PostgresChatMemoryStore.ArchivedMessage;
import com.schemaplexai.service.user.impl.UserMemoryExtractionService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_CALL_ID;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_MAVEN_MODEL_SUMMARY;
import static com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor.DESKTOP_READ_EVIDENCE_HASH;

/** 记录 Team 父会话的用户提问和团队交付。 */
@Component
@RequiredArgsConstructor
class TeamConversationArchive {

    private static final int RECENT_MESSAGE_LIMIT = 6;
    private static final int HISTORY_MESSAGE_CHAR_LIMIT = 450;
    private static final int HTTP_EVIDENCE_LIMIT = 4;
    private static final int CLI_EVIDENCE_LIMIT_PER_OUTCOME = 8;
    private static final int UPSTREAM_CLI_LIMIT_PER_OUTCOME = 4;
    private static final String ARGUMENT_PREFIX = "请求参数: ";
    private static final String DESKTOP_RESULT_PREFIX = "\n工具结果: ";
    private static final Pattern BROKER_ID = Pattern.compile(
            "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern HTTPS_HEAD = Pattern.compile(
            "curl(?: -[fsSIL]+| --(?:head|silent|show-error|fail|location)| --max-time [0-9]{1,3})+"
                    + " (https://[A-Za-z0-9.-]+(?::[0-9]{1,5})?(?:/[A-Za-z0-9._/-]*)?)");
    private static final Pattern HEAD_OPTION = Pattern.compile(" (?:-[fsSL]*I[fsSIL]*|--head) ");
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "HTTP/[0-9.]+ [2-5][0-9]{2}(?: [A-Za-z0-9 .-]{0,80})?");
    private static final String JAR_COMMAND = "java -jar (?:[A-Za-z0-9_-]+/){0,5}[A-Za-z0-9_.-]+\\.jar";
    private static final Pattern NUMERIC_JAR_COMMAND = Pattern.compile(
            JAR_COMMAND + "(?: -?[0-9]{1,12}(?:\\.[0-9]{1,12})?){0,3}");
    private static final Pattern SIMPLE_JAR_CHECK = Pattern.compile(JAR_COMMAND + "(?: [A-Za-z0-9_.-]{1,24}){0,3}");
    private static final Pattern CLI_NUMBER = Pattern.compile("-?[0-9]{1,28}(?:\\.[0-9]{1,16})?");
    private static final Pattern FILE_INSPECTION = Pattern.compile(
            "(?:ls -[alh1]{1,5}|grep -c [@A-Za-z0-9_]{1,50})(?: [A-Za-z0-9_][A-Za-z0-9_./-]{0,160}){1,8}");
    private static final Pattern README_PATH = Pattern.compile("(?i)(?:\\./)?readme(?:\\.md)?");
    private static final Pattern README_BUILD = Pattern.compile(
            "mvn(?: -[A-Za-z]{1,8}){0,3}(?: clean)? (?:package|verify|install)(?![A-Za-z])");
    private static final Pattern README_RUN = Pattern.compile(
            JAR_COMMAND + "(?: (?:<[\\p{L}0-9_-]{1,24}>|[0-9.-]{1,24})){0,3}");
    private static final Pattern README_INLINE_CODE = Pattern.compile("`([^`\\r\\n]{1,180})`");
    private static final String JDK_OPTIONS_NOTE = "NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home=";
    private static final Set<String> WORKSPACE_WRITE_TOOLS = Set.of("sys_write", "sys_edit", "sys_mkdir", "sys_edit_exact");
    private static final Set<String> WORKSPACE_CHECK_COMMANDS = Set.of("pwd", "ls", "git rev-parse --show-toplevel",
            "git rev-parse --show-prefix", "git remote -v", "git status --short", "git status --short --branch",
            "git status --porcelain", "git status --porcelain=v1", "git status --porcelain --untracked-files=all");

    private final CompositeChatMemoryStore chatMemoryStore;
    private final UserMemoryExtractionService userMemoryExtractionService;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public boolean appendOnTransition(AgentExecution execution, String output, BooleanSupplier transition) {
        if (!transition.getAsBoolean()) {
            return false;
        }
        String conversationId = execution.getConversationId();
        String input = execution.getInputPrompt();
        if (StringUtils.hasText(conversationId) && StringUtils.hasText(input) && StringUtils.hasText(output)) {
            chatMemoryStore.appendMessages(conversationId, execution.getId(), List.of(
                    new ArchivedMessage(UserMessage.from(input), input),
                    new ArchivedMessage(AiMessage.from(output), null)
            ));
        }
        return true;
    }

    void saveUserMemory(AgentExecution execution) {
        userMemoryExtractionService.saveAfterCompletion(execution.getTenantId(), execution.getCreatedBy(),
                execution.getAgentId(), execution.getMemoryProjectId(), execution.getId(), execution.getConversationId(),
                Boolean.TRUE.equals(execution.getTemporaryChat()), execution.getMemoryWriteEnabled(),
                () -> chatMemoryStore.loadAuthoredExecutionInputs(execution.getConversationId(), execution.getId()));
    }

    DesktopEvidence desktopVerificationEvidence(String executionId, String conversationId, String verificationCommand) {
        return desktopEvidence(executionId, conversationId, verificationCommand, false);
    }

    DesktopEvidence desktopUpstreamEvidence(String executionId, String conversationId) {
        return desktopEvidence(executionId, conversationId, null, true);
    }

    private DesktopEvidence desktopEvidence(String executionId, String conversationId, String verificationCommand,
                                            boolean upstream) {
        if (!StringUtils.hasText(conversationId) || !StringUtils.hasText(executionId)) {
            return DesktopEvidence.EMPTY;
        }
        var messages = chatMemoryStore.loadExecutionForExtraction(conversationId, executionId);
        var calls = messages.stream().filter(AiMessage.class::isInstance).map(AiMessage.class::cast)
                .filter(AiMessage::hasToolExecutionRequests)
                .flatMap(message -> message.toolExecutionRequests().stream())
                .filter(call -> ("sys_bash".equals(call.name()) || "sys_read".equals(call.name()))
                        && StringUtils.hasText(call.id()))
                .collect(Collectors.groupingBy(call -> call.id()));
        var networkBeforeCli = new ArrayList<ShellReceipt>();
        var networkAfterCli = new ArrayList<ShellReceipt>();
        var currentChecks = new ArrayList<ShellReceipt>();
        ShellReceipt finalReceipt = null;
        boolean workspaceChanged = false;
        boolean artifactsChanged = false;
        boolean cliSeen = false;
        String readme = "";
        for (var message : messages) {
            if (!(message instanceof ToolExecutionResultMessage result)) continue;
            boolean shellCall = "sys_bash".equals(result.toolName());
            var matchingCalls = calls.get(result.id());
            ToolExecutionRequest call = matchingCalls != null && matchingCalls.size() == 1 ? matchingCalls.getFirst() : null;
            if ("sys_read".equals(result.toolName())) {
                String evidence = readmeEvidence(result, call);
                if (evidence != null) readme = evidence;
            }
            if (!shellCall && (result.toolName() == null || !WORKSPACE_WRITE_TOOLS.contains(result.toolName()))) continue;
            ShellReceipt receipt = shellCall && matchingCalls != null && matchingCalls.size() == 1
                    && "sys_bash".equals(call.name()) ? readShellReceipt(result, call.arguments()) : null;
            boolean gitInit = receipt != null && receipt.isRootVerification("git init");
            // 如果配置命令后又执行了 Shell 或写入，它只是一次较早的构建，不能证明后续产物状态。
            if (finalReceipt != null || !shellCall || (receipt == null && result.attributes().containsKey(DESKTOP_CALL_ID))
                    || (receipt != null && !receipt.isRootVerification(verificationCommand) && !receipt.isCheck())) {
                workspaceChanged = true;
                if (gitInit) {
                    currentChecks.removeIf(previous -> WORKSPACE_CHECK_COMMANDS.contains(previous.command()));
                } else {
                    currentChecks.clear();
                    artifactsChanged = true;
                    readme = "";
                }
            }
            finalReceipt = receipt != null && receipt.isRootVerification(verificationCommand) ? receipt : null;
            if (receipt == null) continue;
            if (SIMPLE_JAR_CHECK.matcher(receipt.command()).matches()) cliSeen = true;
            if (HTTPS_HEAD.matcher(receipt.command()).matches()) {
                (cliSeen ? networkAfterCli : networkBeforeCli).add(receipt);
            }
            if (receipt.isCheck()) currentChecks.add(receipt);
        }
        // 最终构建本身可替换产物，CLI 结果仅证明构建前的产物。
        boolean hasFinalReceipt = finalReceipt != null;
        var https = new ArrayList<String>();
        addHttpsEvidence(networkBeforeCli, cliSeen ? "本成员 CLI 前的" : "", https);
        addHttpsEvidence(networkAfterCli.reversed(), "本成员已开始 CLI 后的", https);
        var cliEvidence = new ArrayList<CliEvidence>();
        if (upstream || hasFinalReceipt) {
            var seenCommands = new LinkedHashSet<String>();
            var cli = currentChecks.reversed().stream()
                    .filter(receipt -> SIMPLE_JAR_CHECK.matcher(receipt.command()).matches())
                    .filter(receipt -> seenCommands.add(receipt.command())).toList().reversed();
            int limit = upstream ? UPSTREAM_CLI_LIMIT_PER_OUTCOME : CLI_EVIDENCE_LIMIT_PER_OUTCOME;
            for (boolean successful : List.of(true, false)) {
                cli.stream().filter(receipt -> (receipt.exitCode() == 0) == successful)
                        .map(this::cliEvidence).filter(Objects::nonNull)
                        .limit(limit).forEach(cliEvidence::add);
            }
        }
        return new DesktopEvidence(List.copyOf(https), gitEvidence(currentChecks), List.copyOf(cliEvidence),
                hasFinalReceipt ? verificationEvidence(finalReceipt) : "", readme, workspaceChanged, artifactsChanged);
    }

    private ShellReceipt readShellReceipt(ToolExecutionResultMessage receipt, String originalArguments) {
        DesktopReceipt parsed = readDesktopReceipt(receipt, originalArguments, "sys.bash");
        if (parsed == null || !parsed.args().path("command").isTextual()
                || !parsed.args().path("command").equals(parsed.displayed().path("command"))) return null;
        try {
            JsonNode shell = objectMapper.readTree(parsed.output());
            if (shell == null || !shell.path("exitCode").isIntegralNumber() || !shell.path("exitCode").canConvertToInt()
                    || !shell.path("stdout").isTextual() || !shell.path("stderr").isTextual()) return null;
            int exit = shell.path("exitCode").intValue();
            boolean success = exit == 0;
            if (receipt.isError() == success) return null;
            return new ShellReceipt(parsed.args().path("command").textValue(), workdir(parsed.args()),
                    shell, parsed.brokerId(), receipt.attributes());
        } catch (JsonProcessingException ignored) {
            // 历史回执无法解析时，交接仍保留成员的原有说明。
            return null;
        }
    }

    private DesktopReceipt readDesktopReceipt(ToolExecutionResultMessage receipt, String originalArguments, String toolCode) {
        if (receipt.isError() == null || !receipt.hasSingleText()
                || !(receipt.attributes().get(DESKTOP_CALL_ID) instanceof String brokerId)
                || !BROKER_ID.matcher(brokerId).matches() || !StringUtils.hasText(originalArguments)
                || !Boolean.FALSE.equals(receipt.attributes().get("compressionApplied"))) return null;
        String text = receipt.text();
        if (text == null) return null;
        int separator = text.indexOf(DESKTOP_RESULT_PREFIX);
        if (separator < ARGUMENT_PREFIX.length() || !text.startsWith(ARGUMENT_PREFIX)) return null;
        String body = text.substring(separator + DESKTOP_RESULT_PREFIX.length());
        Integer length = body.length();
        if (!length.equals(receipt.attributes().get("rawChars"))
                || !length.equals(receipt.attributes().get("compressedChars"))) return null;
        try {
            JsonNode args = objectMapper.readTree(originalArguments);
            JsonNode displayed = objectMapper.readTree(text.substring(ARGUMENT_PREFIX.length(), separator));
            JsonNode envelope = objectMapper.readTree(body);
            if (args == null || displayed == null || envelope == null
                    || !receipt.id().equals(envelope.path("callId").textValue())
                    || !toolCode.equals(envelope.path("toolCode").textValue())
                    || !envelope.path("success").isBoolean() || envelope.path("success").booleanValue() == receipt.isError()
                    || (!receipt.isError() && envelope.hasNonNull("errorMessage"))
                    || envelope.hasNonNull("controlAction") || !envelope.path("result").isTextual()) return null;
            return new DesktopReceipt(args, displayed, envelope.path("result").textValue(), brokerId);
        } catch (JsonProcessingException ignored) {
            // 无法和原始调用对应的回执不作为交付证据。
            return null;
        }
    }

    private String readmeEvidence(ToolExecutionResultMessage receipt, ToolExecutionRequest call) {
        if (call == null || !"sys_read".equals(call.name()) || !Boolean.FALSE.equals(receipt.isError())) return null;
        var parsed = readDesktopReceipt(receipt, call.arguments(), "sys.read");
        if (parsed == null || !".".equals(workdir(parsed.args()))) return null;
        var path = parsed.args().path("path");
        if (!path.isTextual() || !README_PATH.matcher(path.textValue()).matches()
                || !path.equals(parsed.displayed().path("path"))
                || parsed.args().size() != (parsed.args().has("workingDirectory") || parsed.args().has("workdir") ? 2 : 1)
                || parsed.output().endsWith("\n[已截断]")) return null;
        String hashInput = objectMapper.createArrayNode().add(parsed.args()).add(parsed.output()).toString();
        if (!HashUtils.sha256Hex(hashInput.getBytes(StandardCharsets.UTF_8))
                .equals(receipt.attributes().get(DESKTOP_READ_EVIDENCE_HASH))) return null;
        String build = documentedCommand(parsed.output(), README_BUILD);
        String run = documentedCommand(parsed.output(), README_RUN);
        if (build.isEmpty() && run.isEmpty()) return "";
        var commands = new ArrayList<String>(2);
        if (!build.isEmpty()) commands.add("构建 `" + build + "`");
        if (!run.isEmpty()) commands.add("运行 `" + run + "`");
        return "`" + path.textValue() + "` 实际内容含" + String.join("、", commands)
                + "（设备回执 `" + parsed.brokerId() + "`）";
    }

    private String documentedCommand(String content, Pattern command) {
        return content.lines().map(String::strip).map(line -> {
            if (command.matcher(line).matches()) return line;
            var code = README_INLINE_CODE.matcher(line);
            while (code.find()) if (command.matcher(code.group(1)).matches()) return code.group(1);
            return "";
        }).filter(StringUtils::hasText).findFirst().orElse("");
    }

    private String workdir(JsonNode args) {
        return args.path("workingDirectory").asText(args.path("workdir").asText("."));
    }

    private void addHttpsEvidence(List<ShellReceipt> receipts, String phase, List<String> evidence) {
        var seenUrls = new LinkedHashSet<String>();
        for (ShellReceipt receipt : receipts) {
            if (evidence.size() >= HTTP_EVIDENCE_LIMIT) break;
            String observation = httpsEvidence(receipt, seenUrls);
            if (StringUtils.hasText(observation)) evidence.add(phase + observation);
        }
    }

    private String httpsEvidence(ShellReceipt receipt, LinkedHashSet<String> seenUrls) {
        var url = HTTPS_HEAD.matcher(receipt.command());
        if (receipt.exitCode() != 0 || receipt.command().length() > 300 || !url.matches()
                || !HEAD_OPTION.matcher(receipt.command()).find() || receipt.shell().path("stdoutTruncated").asBoolean()) {
            return "";
        }
        String status = receipt.stdout().lines().map(String::stripTrailing)
                .filter(line -> HTTP_STATUS.matcher(line).matches()).reduce((previous, current) -> current).orElse("");
        if (!StringUtils.hasText(status) || status.equalsIgnoreCase("HTTP/1.1 200 Connection established")
                || status.equalsIgnoreCase("HTTP/1.0 200 Connection established") || !seenUrls.add(url.group(1))) return "";
        return "HTTPS 实测 `" + receipt.command() + "` 返回 `" + status + "`（exit 0，" + receipt.reference() + "）";
    }

    private List<String> gitEvidence(List<ShellReceipt> receipts) {
        var evidence = new ArrayList<String>();
        ShellReceipt root = latestRootShellReceipt(receipts, "git rev-parse --show-toplevel");
        ShellReceipt prefix = latestRootShellReceipt(receipts, "git rev-parse --show-prefix");
        ShellReceipt cwd = latestRootShellReceipt(receipts, "pwd");
        if (root != null && !root.stdout().strip().isEmpty()) {
            boolean cwdMatches = cwd != null && root.stdout().strip().equals(cwd.stdout().strip());
            if (cwdMatches || prefix != null && prefix.stdout().isBlank()) {
                evidence.add("Git 根目录与当前目录相同（" + root.reference() + "，"
                        + (cwdMatches ? cwd.reference() : prefix.reference()) + "）");
            } else {
                evidence.add("`git rev-parse --show-toplevel` 返回 Git 根目录，需结合工作区路径核对（"
                        + root.reference() + "）");
            }
        }
        ShellReceipt remote = latestRootShellReceipt(receipts, "git remote -v");
        if (remote != null) {
            evidence.add("`git remote -v` stdout " + (remote.stdout().isBlank() ? "为空" : "有内容")
                    + "（exit 0，" + remote.reference() + "）");
        }
        return List.copyOf(evidence);
    }

    private ShellReceipt latestRootShellReceipt(List<ShellReceipt> receipts, String command) {
        return receipts.reversed().stream().filter(receipt -> command.equals(receipt.command()))
                .findFirst().filter(receipt -> receipt.isRootVerification(command)
                        && !receipt.shell().path("stdoutTruncated").asBoolean()).orElse(null);
    }

    private CliEvidence cliEvidence(ShellReceipt receipt) {
        if (!".".equals(receipt.workdir())
                || receipt.shell().path("stdoutTruncated").asBoolean()
                || receipt.shell().path("stderrTruncated").asBoolean()) return null;
        String stdout = receipt.stdout().stripTrailing();
        boolean appStderr = receipt.shell().path("stderr").textValue().lines()
                .anyMatch(line -> !line.isBlank() && !line.startsWith(JDK_OPTIONS_NOTE)
                        && !line.startsWith("Picked up JDK_JAVA_OPTIONS: -Duser.home="));
        if ((!stdout.isEmpty() && !CLI_NUMBER.matcher(stdout).matches())
                || (receipt.exitCode() == 0 && !NUMERIC_JAR_COMMAND.matcher(receipt.command()).matches())) return null;
        return new CliEvidence(receipt.command(), receipt.exitCode(), stdout, appStderr, receipt.reference());
    }

    private String verificationEvidence(ShellReceipt receipt) {
        String result = "配置的最终验收命令 `" + receipt.command() + "`（exit 0，" + receipt.reference() + "）";
        if (!(receipt.attributes().get(DESKTOP_MAVEN_MODEL_SUMMARY) instanceof String text)) return result;
        try {
            JsonNode summary = objectMapper.readTree(text);
            var counters = List.of("tests", "failures", "errors", "skipped");
            if (summary != null && receipt.command().equals(summary.path("command").textValue())
                    && counters.stream().allMatch(key -> summary.path(key).isIntegralNumber()
                            && summary.path(key).canConvertToInt() && summary.path(key).intValue() >= 0)) {
                return result + "。Maven 汇总 " + summary.path("tests").intValue() + " 项测试，"
                        + summary.path("failures").intValue() + " 失败，" + summary.path("errors").intValue()
                        + " 错误，" + summary.path("skipped").intValue() + " 跳过。";
            }
        } catch (JsonProcessingException ignored) {
            // 旧版缺少结构化 Maven 汇总时只交接已核对的退出码。
        }
        return result;
    }

    private record ShellReceipt(String command, String workdir, JsonNode shell,
                                String brokerId, Map<String, Object> attributes) {
        private int exitCode() {
            return shell.path("exitCode").intValue();
        }

        private String stdout() {
            return shell.path("stdout").textValue();
        }

        private String reference() {
            return "设备回执 `" + brokerId + "`";
        }

        private boolean isRootVerification(String verificationCommand) {
            return command.equals(verificationCommand) && ".".equals(workdir) && exitCode() == 0;
        }

        private boolean isCheck() {
            return WORKSPACE_CHECK_COMMANDS.contains(command) || FILE_INSPECTION.matcher(command).matches()
                    || SIMPLE_JAR_CHECK.matcher(command).matches()
                    || (HTTPS_HEAD.matcher(command).matches() && HEAD_OPTION.matcher(command).find());
        }
    }

    private record DesktopReceipt(JsonNode args, JsonNode displayed, String output, String brokerId) {}

    record CliEvidence(String command, int exitCode, String stdout, boolean appStderr, String reference) {
        String describe(boolean beforeFinalVerification) {
            return (beforeFinalVerification ? "最终验收命令之前的 CLI" : "成员已执行的 CLI")
                    + " `" + command + "`（exit " + exitCode
                    + "，stdout " + (stdout.isEmpty() ? "为空" : "`" + stdout + "`")
                    + "，应用 stderr " + (appStderr ? "有输出" : "为空") + "，" + reference + "）";
        }
    }

    record DesktopEvidence(List<String> https, List<String> git, List<CliEvidence> cli, String build,
                           String readme, boolean workspaceChanged, boolean artifactsChanged) {
        static final DesktopEvidence EMPTY = new DesktopEvidence(List.of(), List.of(), List.of(), "", "", false, false);

        boolean hasChecks() {
            return !https.isEmpty() || !git.isEmpty() || !cli.isEmpty() || StringUtils.hasText(readme);
        }

        List<String> lines() {
            var result = new ArrayList<>(https);
            result.addAll(git);
            cli.stream().map(check -> check.describe(StringUtils.hasText(build))).forEach(result::add);
            if (StringUtils.hasText(readme)) result.add(readme);
            if (StringUtils.hasText(build)) result.add(build);
            return List.copyOf(result);
        }

        DesktopEvidence withMissingChecksFrom(DesktopEvidence upstream) {
            var checks = new LinkedHashMap<String, CliEvidence>();
            cli.forEach(check -> checks.put(check.command(), check));
            if (!artifactsChanged && StringUtils.hasText(build)) {
                upstream.cli().forEach(check -> checks.putIfAbsent(check.command(), check));
            }
            var merged = new ArrayList<CliEvidence>();
            for (boolean successful : List.of(true, false)) {
                checks.values().stream().filter(check -> (check.exitCode() == 0) == successful)
                        .limit(CLI_EVIDENCE_LIMIT_PER_OUTCOME).forEach(merged::add);
            }
            List<String> observations = new ArrayList<>(upstream.https());
            observations.addAll(https);
            return new DesktopEvidence(observations.stream().distinct().limit(HTTP_EVIDENCE_LIMIT).toList(),
                    git.isEmpty() && !workspaceChanged ? upstream.git() : git,
                    List.copyOf(merged), build, readme, workspaceChanged, artifactsChanged);
        }
    }

    String previousTurns(AgentExecution execution, boolean temporaryChat) {
        if (temporaryChat || !StringUtils.hasText(execution.getConversationId())) {
            return "";
        }
        return chatMemoryStore.loadWindow(execution.getConversationId(), execution.getId(), RECENT_MESSAGE_LIMIT)
                .stream()
                .filter(message -> message instanceof UserMessage || message instanceof AiMessage)
                .map(message -> {
                    String text = ChatMessageText.textOf(message).strip();
                    String role = message instanceof UserMessage ? "用户" : "团队";
                    return text.isEmpty() ? "" : role + "：" + text.substring(0, Math.min(text.length(), HISTORY_MESSAGE_CHAR_LIMIT));
                })
                .filter(StringUtils::hasText)
                .collect(Collectors.joining("\n"));
    }
}
