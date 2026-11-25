package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.desktop.DesktopToolExecutor;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 现有平台 ToolExecutor 到 LangChain4j ToolExecutor 的桥接器
 */
@Slf4j
@RequiredArgsConstructor
public class LangChain4jBindingToolExecutor implements dev.langchain4j.service.tool.ToolExecutor {

    public static final String DESKTOP_CALL_ID = "desktopCallId";
    public static final String DESKTOP_READ_EVIDENCE_HASH = "desktopReadEvidenceHash";
    public static final String DESKTOP_MAVEN_MODEL_SUMMARY = "desktopMavenModelSummary";
    public static final String DESKTOP_MAVEN_SOURCE_FAILURE = "desktopMavenSourceFailure";
    private static final int MIN_MAVEN_STDOUT_CHARS = 1200;
    // 旧桌面端在此长度截断 stdout，回执中没有单独的截断标记。
    private static final int DESKTOP_STDOUT_LIMIT = 8_000;
    private static final String DESKTOP_TRUNCATION_NOTICE = "[中间输出已截断]";
    private static final int MAX_EXCERPT_LINE_CHARS = 160;
    private static final int MAX_FAILURE_LINE_CHARS = 280;
    private static final String JDK_OPTIONS_NOTE = "NOTE: Picked up JDK_JAVA_OPTIONS: -Duser.home=";
    private static final String MAVEN_FLAG = "\\h+(?:-B|--batch-mode|-ntp|--no-transfer-progress)";
    private static final Pattern MAVEN_TEST_COMMAND = Pattern.compile(
            "mvn(?:" + MAVEN_FLAG + ")*\\h+(?:clean\\h+)?(?:test|package|verify)(?:" + MAVEN_FLAG + ")*");
    private static final Pattern QUIET_MAVEN_BUILD = Pattern.compile(
            "mvn\\h+(?:-q|--quiet)\\h+(?:clean\\h+)?(?:package|verify)");
    private static final Pattern ANSI_COLOR = Pattern.compile("\u001b\\[[0-9;]*m");
    private static final Pattern MAVEN_SOURCE_ERROR = Pattern.compile("^\\[ERROR] .+:\\[\\d+(?:,\\d+)?] .+");
    private static final Pattern MAVEN_TEST_SUMMARY = Pattern.compile(
            "\\[INFO] Tests run: ([0-9]{1,9}), Failures: ([0-9]{1,9}), Errors: ([0-9]{1,9}), "
                    + "Skipped: ([0-9]{1,9})(, Time elapsed: .+ -- in .+)?");
    private static final String MAVEN_SUCCESS = "[INFO] BUILD SUCCESS";
    private static final String MAVEN_SEPARATOR = "[INFO] ------------------------------------------------------------------------";

    private final ObjectMapper objectMapper;
    private final com.schemaplexai.service.agent.tool.executor.ToolExecutor delegate;
    private final String tenantId;
    private final String agentId;
    private final AgentToolBinding binding;
    private final SandboxPolicy sandboxPolicy;

    boolean isDesktopTool() {
        return delegate instanceof DesktopToolExecutor;
    }

    @Override
    public ToolExecutionResult executeWithContext(ToolExecutionRequest request, dev.langchain4j.invocation.InvocationContext invocationContext) {
        String actualToolCode = binding != null && binding.getToolCode() != null ? binding.getToolCode() : request.name();
        ToolCall toolCall = ToolCall.builder()
                .callId(request.id())
                .toolCode(actualToolCode)
                .arguments(parseArguments(request.arguments()))
                .build();
        ToolResult result = delegate.execute(tenantId, agentId, binding, toolCall, sandboxPolicy);
        return ToolExecutionResult.builder()
                .isError(result == null || !result.isSuccess())
                .result(result)
                .resultText(serialize(result))
                .attributes(desktopModelAttributes(toolCall, result))
                .build();
    }

    private Map<String, Object> desktopModelAttributes(ToolCall call, ToolResult result) {
        if (!isDesktopTool() || result == null) {
            return Map.of();
        }
        Map<String, Object> attributes = new HashMap<>(2);
        if (result.getDesktopCallId() != null) {
            attributes.put(DESKTOP_CALL_ID, result.getDesktopCallId().toString());
        }
        if (result.getResult() == null || result.getResult().isNull()) {
            return Map.copyOf(attributes);
        }
        if ("sys.bash".equals(call.getToolCode())) {
            String summary = summarizeMavenOutput(call, result, attributes);
            if (summary != null) {
                attributes.put(DESKTOP_MAVEN_MODEL_SUMMARY, summary);
            }
        } else if ("sys.read".equals(call.getToolCode()) && result.isSuccess()) {
            // 来源由服务端决定，桌面回传只贡献内容，无法指定元数据。
            var evidence = objectMapper.createArrayNode().add(call.getArguments()).add(result.getResult());
            attributes.put(DESKTOP_READ_EVIDENCE_HASH, HashUtils.sha256Hex(evidence.toString().getBytes(StandardCharsets.UTF_8)));
        }
        return Map.copyOf(attributes);
    }

    private String summarizeMavenOutput(ToolCall call, ToolResult result, Map<String, Object> attributes) {
        JsonNode arguments = call.getArguments();
        String command = arguments == null ? "" : arguments.path("command").asText("").strip();
        boolean quietBuild = QUIET_MAVEN_BUILD.matcher(command).matches();
        if ((!quietBuild && !MAVEN_TEST_COMMAND.matcher(command).matches()) || !result.getResult().isTextual()
                || result.getControlAction() != null) {
            return null;
        }
        JsonNode output;
        try {
            // 桌面 Shell 将退出码、标准输出和错误输出作为 JSON 字符串回传。
            output = objectMapper.readTree(result.getResult().textValue());
        } catch (Exception ignored) {
            return null;
        }
        if (output == null || !output.path("exitCode").isIntegralNumber()
                || !output.path("exitCode").canConvertToInt()
                || !output.path("stdout").isTextual() || !output.path("stderr").isTextual()) {
            return null;
        }
        int exitCode = output.path("exitCode").intValue();
        String stdout = output.path("stdout").textValue();
        if (quietBuild) {
            return summarizeQuietMavenBuild(call, result, command, output, exitCode);
        }
        if (exitCode != 0) {
            return summarizeMavenFailure(call, result, command, output, exitCode, attributes);
        }
        if (!result.isSuccess() || result.getErrorMessage() != null) return null;
        if (stdout.length() < MIN_MAVEN_STDOUT_CHARS || hasMavenStderr(output.path("stderr").textValue())) {
            return null;
        }
        List<String> lines = ANSI_COLOR.matcher(stdout).replaceAll("").lines().map(String::strip)
                .filter(line -> !line.isEmpty()).toList();
        boolean capturedHeadAndTail = output.path("stdoutTruncated").isBoolean()
                && output.path("stdoutTruncated").booleanValue() && lines.contains(DESKTOP_TRUNCATION_NOTICE);
        int totalTime = lines.size() - 3;
        int finish = totalTime - 1;
        if (finish > 0 && MAVEN_SEPARATOR.equals(lines.get(finish))) {
            finish--;
        }
        if (finish < 1 || !MAVEN_SUCCESS.equals(lines.get(finish))
                || !lines.get(totalTime).startsWith("[INFO] Total time:")
                || !lines.get(totalTime + 1).startsWith("[INFO] Finished at:")
                || !MAVEN_SEPARATOR.equals(lines.getLast())) {
            return summarizeIncompleteMavenOutput(call, result, command, stdout, lines);
        }
        Matcher total = findMavenTestTotal(lines, finish);
        if (total == null) {
            if (capturedHeadAndTail && result.getDesktopCallId() != null && call.getCallId() != null
                    && call.getCallId().equals(result.getCallId())
                    && lines.subList(0, finish).stream().noneMatch(line -> line.startsWith("[ERROR]")
                    || line.startsWith("[WARN") || line.startsWith("[INFO] BUILD ") || line.contains("Tests run:"))) {
                return objectMapper.createObjectNode()
                        .put("command", command).put("exitCode", 0).put("logAtCaptureLimit", true)
                        .put("testSummary", "输出已截断，未核验测试数量及状态。")
                        .put("logEnd", finalMavenExcerpt(lines, finish)).toString();
            }
            return null;
        }
        // 摘要只用于后续模型请求，原回执和会话存档仍保留原文。
        return objectMapper.createObjectNode()
                .put("command", command)
                .put("exitCode", 0)
                .put("tests", Integer.parseInt(total.group(1)))
                .put("failures", Integer.parseInt(total.group(2)))
                .put("errors", Integer.parseInt(total.group(3)))
                .put("skipped", Integer.parseInt(total.group(4)))
                .put("logStart", boundedExcerpt(lines.stream()
                        .filter(line -> line.startsWith("[INFO]") && !line.equals(MAVEN_SEPARATOR))
                        .limit(2).toList()))
                .put("logEnd", finalMavenExcerpt(lines, finish))
                .toString();
    }

    private String summarizeQuietMavenBuild(ToolCall call, ToolResult result, String command,
                                            JsonNode output, int exitCode) {
        if (exitCode != 0 || !result.isSuccess() || result.getErrorMessage() != null
                || result.getDesktopCallId() == null || call.getCallId() == null
                || !call.getCallId().equals(result.getCallId()) || !call.getToolCode().equals(result.getToolCode())
                || !ANSI_COLOR.matcher(output.path("stdout").textValue()).replaceAll("").isBlank()
                || hasMavenStderr(output.path("stderr").textValue())) {
            return null;
        }
        for (String field : List.of("stdoutTruncated", "stderrTruncated")) {
            JsonNode truncated = output.path(field);
            if (!truncated.isMissingNode() && (!truncated.isBoolean() || truncated.booleanValue())) {
                return null;
            }
        }
        return objectMapper.createObjectNode().put("command", command).put("exitCode", 0).put("quietBuild", true)
                .put("testSummary", "静默构建退出码为 0，未观测测试数量和状态。").toString();
    }

    private String summarizeMavenFailure(ToolCall call, ToolResult result, String command,
                                         JsonNode output, int exitCode, Map<String, Object> attributes) {
        JsonNode truncation = output.path("stdoutTruncated");
        if (result.isSuccess() || result.getDesktopCallId() == null
                || call.getCallId() == null || !call.getCallId().equals(result.getCallId())
                || (!truncation.isMissingNode() && !truncation.isBoolean())) {
            return null;
        }
        List<String> errors = ANSI_COLOR.matcher(output.path("stdout").textValue()).replaceAll("")
                .lines().map(String::strip).filter(line -> line.startsWith("[ERROR]"))
                .distinct().sorted(Comparator.comparing((String line) -> !MAVEN_SOURCE_ERROR.matcher(line).matches()))
                .limit(8).toList();
        if (errors.isEmpty()) return null;
        if (errors.stream().anyMatch(line -> MAVEN_SOURCE_ERROR.matcher(line).matches())) {
            attributes.put(DESKTOP_MAVEN_SOURCE_FAILURE, true);
        }
        var summary = objectMapper.createObjectNode().put("command", command).put("exitCode", exitCode)
                .put("logAtCaptureLimit", truncation.booleanValue()).put("errors", boundedExcerpt(errors, MAX_FAILURE_LINE_CHARS));
        String stderr = boundedExcerpt(ANSI_COLOR.matcher(output.path("stderr").textValue()).replaceAll("")
                .lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith(JDK_OPTIONS_NOTE))
                .distinct().limit(3).toList(), MAX_FAILURE_LINE_CHARS);
        if (!stderr.isEmpty()) summary.put("stderr", stderr);
        return summary.toString();
    }

    private String summarizeIncompleteMavenOutput(ToolCall call, ToolResult result, String command,
                                                  String stdout, List<String> lines) {
        if (result.getDesktopCallId() == null || !call.getCallId().equals(result.getCallId())
                || stdout.length() != DESKTOP_STDOUT_LIMIT || lines.isEmpty()
                || !"[INFO] Scanning for projects...".equals(lines.getFirst())
                || lines.stream().anyMatch(line -> line.startsWith("[ERROR]") || line.startsWith("[WARN")
                        || line.startsWith("[INFO] BUILD "))) {
            return null;
        }
        return objectMapper.createObjectNode()
                .put("command", command)
                .put("exitCode", 0)
                .put("logAtCaptureLimit", true)
                .put("testSummary", "未收到测试汇总，测试数量和状态需要从报告核验。")
                .put("logStart", boundedExcerpt(lines.stream()
                        .filter(line -> line.startsWith("[INFO]") && !line.equals(MAVEN_SEPARATOR))
                        .limit(2).toList()))
                .toString();
    }

    private boolean hasMavenStderr(String stderr) {
        return ANSI_COLOR.matcher(stderr).replaceAll("").lines().map(String::strip)
                .anyMatch(line -> !line.isEmpty() && !line.startsWith(JDK_OPTIONS_NOTE));
    }

    private Matcher findMavenTestTotal(List<String> lines, int finish) {
        Matcher total = null;
        long suiteTests = 0;
        long suiteSkipped = 0;
        boolean hasSuites = false;
        for (String line : lines.subList(0, finish)) {
            if (line.startsWith("[ERROR]") || line.startsWith("[WARNING]") || line.startsWith("[WARN]")
                    || line.startsWith("[INFO] BUILD ")) {
                return null;
            }
            if (!line.contains("Tests run:")) {
                continue;
            }
            Matcher summary = MAVEN_TEST_SUMMARY.matcher(line);
            int tests;
            int skipped;
            if (!summary.matches() || Integer.parseInt(summary.group(2)) != 0
                    || Integer.parseInt(summary.group(3)) != 0) {
                return null;
            }
            tests = Integer.parseInt(summary.group(1));
            skipped = Integer.parseInt(summary.group(4));
            if (skipped > tests) {
                return null;
            }
            if (summary.group(5) == null) {
                if (total != null || tests == skipped) {
                    return null;
                }
                total = summary;
            } else {
                hasSuites = true;
                suiteTests += tests;
                suiteSkipped += skipped;
            }
        }
        if (total != null && hasSuites && (suiteTests != Integer.parseInt(total.group(1))
                || suiteSkipped != Integer.parseInt(total.group(4)))) {
            return null;
        }
        return total;
    }

    private String finalMavenExcerpt(List<String> lines, int finish) {
        int archive = finish - 2;
        int start = archive >= 0 && lines.get(archive).startsWith("[INFO] Building jar:") ? archive : finish;
        return boundedExcerpt(lines.subList(start, lines.size() - 1).stream()
                .filter(line -> !line.equals(MAVEN_SEPARATOR)).toList());
    }

    private String boundedExcerpt(List<String> lines) {
        return boundedExcerpt(lines, MAX_EXCERPT_LINE_CHARS);
    }

    private String boundedExcerpt(List<String> lines, int limit) {
        return String.join("\n", lines.stream()
                .map(line -> line.length() <= limit ? line : line.substring(0, limit) + "…")
                .toList());
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        return executeWithContext(request, null).resultText();
    }

    private JsonNode parseArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(arguments);
        } catch (Exception exception) {
            log.warn("工具参数 JSON 解析失败: toolName={}, error={}", binding != null ? binding.getToolCode() : null, exception.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String serialize(ToolResult result) {
        if (result == null) {
            return "{\"success\":false,\"errorMessage\":\"工具执行结果为空\"}";
        }
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception exception) {
            return result.getErrorMessage() != null ? result.getErrorMessage() : "{}";
        }
    }
}
