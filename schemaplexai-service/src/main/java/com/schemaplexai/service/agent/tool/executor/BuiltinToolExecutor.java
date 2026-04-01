package com.schemaplexai.service.agent.tool.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.ToolExecutionStatusEnum;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.executor.os.CommandValidator;
import com.schemaplexai.service.agent.tool.executor.os.ShellCommandAdapter;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * 系统内置工具执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinToolExecutor implements ToolExecutor {

    private static final Set<String> SUPPORTED_CODES = Set.of(
            "sys.read", "sys.write", "sys.edit", "sys.bash", "sys.glob", "sys.grep",
            "sys.ls", "sys.mkdir", "sys.rm", "sys.cp", "sys.mv", "sys.stat"
    );

    private static final long PROCESS_TIMEOUT_SECONDS = 30;

    private final ObjectMapper objectMapper;
    private final List<ShellCommandAdapter> adapters;
    private final CommandValidator commandValidator;
    private final ToolExecutionLogService logService;
    private final WorkspacePathResolver workspacePathResolver;

    @Override
    public String sourceType() {
        return SourceTypeEnum.BUILTIN.getCode();
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        LocalDateTime startAt = LocalDateTime.now();
        if (toolCall == null || !StringUtils.hasText(toolCall.getToolCode())) {
            logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                    SourceTypeEnum.BUILTIN.getCode(), null, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "系统工具调用参数无效");
            return failure(toolCall, "系统工具调用参数无效");
        }

        String toolCode = toolCall.getToolCode().trim();
        if (!SUPPORTED_CODES.contains(toolCode)) {
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "不支持的系统工具");
            return failure(toolCall, "不支持的系统工具: " + toolCode);
        }

        try {
            String osType = System.getProperty("os.name").toLowerCase();
            ShellCommandAdapter adapter = adapters.stream()
                    .filter(a -> a.supports(osType))
                    .findFirst()
                    .orElseThrow(() -> new UnsupportedOperationException("不支持的操作系统: " + osType));

            Map<String, Object> args = objectMapper.convertValue(toolCall.getArguments(), Map.class);
            if (args == null) {
                args = Map.of();
            }
            Map<String, Object> safeArgsForLog = sanitizeArgsForLog(args);
            if (!commandValidator.validateToolArguments(toolCode, args)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), safeArgsForLog, null, "系统工具参数校验失败");
                return failure(toolCall, "系统工具参数校验失败");
            }

            String command = adapter.adaptCommand(toolCode, args);

            if (!commandValidator.isCommandAllowed(toolCode, command)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), safeArgsForLog, null, "命令不在白名单");
                return failure(toolCall, "命令不在白名单");
            }

            ProcessBuilder processBuilder = new ProcessBuilder(adapter.wrapShellCommand(command));
            Path workingDirectory = resolveWorkingDirectory(args);
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory.toFile());
            }
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            FutureTask<String> outputTask = new FutureTask<>(() -> readProcessOutput(process));
            Thread outputReader = new Thread(outputTask, "builtin-tool-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
                outputTask.cancel(true);
                String error = "命令执行超时";
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), safeArgsForLog, null, error);
                return failure(toolCall, error);
            }

            String output;
            try {
                output = outputTask.get(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                output = "";
            } catch (Exception e) {
                output = "";
            }
            int exitCode = process.exitValue();
            LocalDateTime endAt = LocalDateTime.now();

            if (exitCode == 0) {
                Map<String, Object> result = Map.of("output", output);
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.SUCCESS.getCode(), startAt, endAt, safeArgsForLog, result, null);
                return ToolResult.builder()
                        .callId(toolCall.getCallId())
                        .toolCode(toolCode)
                        .success(true)
                        .result(objectMapper.valueToTree(result))
                        .build();
            } else {
                String error = "命令执行失败，退出码: " + exitCode;
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, endAt, safeArgsForLog, null, error);
                return failure(toolCall, error);
            }
        } catch (Exception e) {
            log.error("系统工具执行失败: toolCode={}, error={}", toolCode, e.getMessage());
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.ERROR.getCode(), startAt, LocalDateTime.now(), null, null, e.getMessage());
            return failure(toolCall, "执行失败: " + e.getMessage());
        }
    }

    private String readProcessOutput(Process process) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        return output.toString();
    }

    private Map<String, Object> sanitizeArgsForLog(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>(args);
        if (copy.containsKey("content")) {
            copy.put("content", "[REDACTED]");
        }
        if (copy.containsKey("command")) {
            copy.put("command", "[REDACTED]");
        }
        return copy;
    }

    private Path resolveWorkingDirectory(Map<String, Object> args) {
        if (args == null || !args.containsKey("workdir")) {
            return null;
        }
        String workdir = String.valueOf(args.get("workdir")).trim();
        if (!StringUtils.hasText(workdir)) {
            return null;
        }
        return workspacePathResolver.validateWithinWorkspaceRoot(workdir);
    }

    private ToolResult failure(ToolCall toolCall, String message) {
        return ToolResult.builder()
                .callId(toolCall != null ? toolCall.getCallId() : null)
                .toolCode(toolCall != null ? toolCall.getToolCode() : null)
                .success(false)
                .result(objectMapper.nullNode())
                .errorMessage(message)
                .build();
    }
}
