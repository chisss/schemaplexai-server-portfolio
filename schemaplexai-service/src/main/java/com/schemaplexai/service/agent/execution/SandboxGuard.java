package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * 沙箱守卫
 */
@Component
public class SandboxGuard {

    public String validateTool(SandboxPolicy policy, AgentToolBinding binding, ToolCall toolCall) {
        if (policy == null || toolCall == null || !StringUtils.hasText(toolCall.getToolCode())) {
            return null;
        }
        Set<String> allowedToolCodes = policy.getAllowedToolCodes();
        if (!CollectionUtils.isEmpty(allowedToolCodes) && !allowedToolCodes.contains(toolCall.getToolCode())) {
            return "工具未在沙箱白名单内: " + toolCall.getToolCode();
        }
        String sourceType = binding != null && StringUtils.hasText(binding.getSourceType())
                ? binding.getSourceType().trim().toLowerCase()
                : SourceTypeEnum.BUILTIN.getCode();
        if (!policy.isNetworkEgressEnabled() && !SourceTypeEnum.BUILTIN.getCode().equals(sourceType)) {
            return "当前沙箱策略禁止外部网络工具: " + toolCall.getToolCode();
        }
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && "sys.bash".equalsIgnoreCase(toolCall.getToolCode())) {
            return "STRICT 沙箱禁止 sys.bash";
        }
        return null;
    }

    public void validateBuiltinExecution(SandboxPolicy policy, String toolCode, Map<String, Object> args, Path workingDirectory, String command) {
        if (policy == null) {
            return;
        }
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && "sys.bash".equalsIgnoreCase(toolCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "STRICT 沙箱禁止 sys.bash");
        }
        if (workingDirectory != null && !CollectionUtils.isEmpty(policy.getAllowedPathPrefixes())) {
            boolean allowed = policy.getAllowedPathPrefixes().stream()
                    .anyMatch(prefix -> workingDirectory.normalize().startsWith(prefix.normalize()));
            if (!allowed) {
                throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作目录超出沙箱允许范围");
            }
        }
        if (!policy.isNetworkEgressEnabled() && StringUtils.hasText(command) && command.toLowerCase().contains("curl ")) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前沙箱策略禁止外部网络访问");
        }
    }
}
