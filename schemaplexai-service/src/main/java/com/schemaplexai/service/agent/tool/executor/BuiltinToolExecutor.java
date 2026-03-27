package com.schemaplexai.service.agent.tool.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.enums.ToolExecutionStatusEnum;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.executor.os.ShellCommandAdapter;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.agent.tool.model.ToolResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 系统内置工具执行器
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuiltinToolExecutor implements ToolExecutor {

    private static final Set<String> SUPPORTED_CODES = Set.of(
            "sys.read", "sys.write", "sys.edit", "sys.bash", "sys.glob", "sys.grep"
    );

    private static final Set<String> COMMAND_WHITELIST = Set.of(
            "cat", "echo", "ls", "find", "grep", "pwd", "date"
    );

    private final ObjectMapper objectMapper;
    private final List<ShellCommandAdapter> adapters;
    private final ToolExecutionLogService logService;

    @Override
    public String sourceType() {
        return "builtin";
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        LocalDateTime startAt = LocalDateTime.now();
        if (toolCall == null || !StringUtils.hasText(toolCall.getToolCode())) {
            logService.logExecution(tenantId, agentId, null, toolCall != null ? toolCall.getCallId() : null,
                    "builtin", null, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "系统工具调用参数无效");
            return failure(toolCall, "系统工具调用参数无效");
        }

        String toolCode = toolCall.getToolCode().trim();
        if (!SUPPORTED_CODES.contains(toolCode)) {
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    "builtin", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), null, null, "不支持的系统工具");
            return failure(toolCall, "不支持的系统工具: " + toolCode);
        }

        try {
            String osType = System.getProperty("os.name").toLowerCase();
            ShellCommandAdapter adapter = adapters.stream()
                    .filter(a -> a.supports(osType))
                    .findFirst()
                    .orElseThrow(() -> new UnsupportedOperationException("不支持的操作系统: " + osType));

            Map<String, Object> args = objectMapper.convertValue(toolCall.getArguments(), Map.class);
            String command = adapter.adaptCommand(toolCode, args);

            if (!isCommandAllowed(command)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "builtin", toolCode, "blocked", startAt, LocalDateTime.now(), args, null, "命令不在白名单");
                return failure(toolCall, "命令不在白名单: " + command);
            }

            Process process = Runtime.getRuntime().exec(command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            int exitCode = process.waitFor();
            LocalDateTime endAt = LocalDateTime.now();

            if (exitCode == 0) {
                Map<String, Object> result = Map.of("output", output.toString());
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "builtin", toolCode,  ToolExecutionStatusEnum.SUCCESS.getCode(), startAt, endAt, args, result, null);
                return ToolResult.builder()
                        .callId(toolCall.getCallId())
                        .toolCode(toolCode)
                        .success(true)
                        .result(objectMapper.valueToTree(result))
                        .build();
            } else {
                String error = "命令执行失败，退出码: " + exitCode;
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        "builtin", toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, endAt, args, null, error);
                return failure(toolCall, error);
            }
        } catch (Exception e) {
            log.error("系统工具执行失败: toolCode={}, error={}", toolCode, e.getMessage());
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    "builtin", toolCode,  ToolExecutionStatusEnum.ERROR.getCode(), startAt, LocalDateTime.now(), null, null, e.getMessage());
            return failure(toolCall, "执行失败: " + e.getMessage());
        }
    }

    private boolean isCommandAllowed(String command) {
        String firstWord = command.split("\\s+")[0];
        return COMMAND_WHITELIST.contains(firstWord);
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
