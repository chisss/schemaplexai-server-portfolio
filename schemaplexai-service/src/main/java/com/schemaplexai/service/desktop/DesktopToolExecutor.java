package com.schemaplexai.service.desktop;

import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.executor.ToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

import java.util.List;

/** 每次执行独立构造，身份与目标显式传递，不依赖线程局部变量。 */
@RequiredArgsConstructor
public class DesktopToolExecutor implements ToolExecutor {

    private final DesktopToolBroker broker;
    private final AgentExecutionContext context;
    private final DesktopExecutionTarget target;
    private final SandboxGuard sandboxGuard;

    @Override
    public String sourceType() {
        return SourceTypeEnum.BUILTIN.getCode();
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall call) {
        return execute(tenantId, agentId, binding, call, context.getSandboxPolicy());
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall call,
                              SandboxPolicy sandboxPolicy) {
        String error = null;
        if (!context.getTenantId().equals(tenantId) || !context.getAgentId().equals(agentId)) {
            error = "本机工具执行上下文不匹配";
        } else {
            error = validateRelativePaths(call);
        }
        if (!StringUtils.hasText(error) && "sys.bash".equals(call.getToolCode())) {
            String command = call.getArguments() == null ? null : call.getArguments().path("command").asText(null);
            error = sandboxGuard.checkCommand(command, sandboxPolicy);
        }
        if (StringUtils.hasText(error)) {
            return ToolResult.builder().callId(call.getCallId()).toolCode(call.getToolCode())
                    .success(false).errorMessage(error).build();
        }
        return broker.execute(context, target, call);
    }

    private String validateRelativePaths(ToolCall call) {
        if (call.getArguments() == null || !call.getArguments().isObject()) {
            return "本机工具参数必须为对象";
        }
        for (String key : List.of("path", "workdir", "workingDirectory", "cwd", "source", "target")) {
            var path = call.getArguments().get(key);
            if (path == null || path.isNull()) {
                continue;
            }
            String value = path.asText().replace('\\', '/');
            if (!path.isTextual() || value.startsWith("/") || value.startsWith("~") || value.contains(":")) {
                return "本机工具文件参数必须使用授权工作区相对路径: " + key;
            }
            if (List.of(value.split("/")).contains("..")) {
                return "本机工具文件参数禁止越过授权工作区: " + key;
            }
        }
        return null;
    }
}
