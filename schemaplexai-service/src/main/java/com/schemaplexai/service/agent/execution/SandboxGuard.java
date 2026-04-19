package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 沙箱守卫
 */
@Component
public class SandboxGuard {

    private static final Pattern ENV_ASSIGNMENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=\\S+$");
    private static final Pattern SHELL_SUBSTITUTION_PATTERN = Pattern.compile("`|\\$\\(");
    private static final Pattern REDIRECTION_PATTERN = Pattern.compile("(^|\\s)(\\d*>>?|<<?|&>)");
    private static final Pattern COMMAND_CHAIN_SPLITTER = Pattern.compile("\\|\\||&&|[|;]");
    private static final Set<String> BLOCKED_SHELL_PREFIXES = Set.of("sudo", "su");

    @Value("${schemaplexai.sandbox.allowed-commands:ls,cat,head,tail,grep,find,wc,sort,uniq,diff,echo,pwd,date,whoami}")
    private String allowedCommandsConfig;

    private Set<String> configuredAllowedCommands = Set.of();

    @PostConstruct
    void init() {
        configuredAllowedCommands = parseAllowedCommands(allowedCommandsConfig);
    }

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
        if (!policy.isNetworkEgressEnabled() && isNetworkBuiltinTool(toolCall.getToolCode())) {
            return "当前沙箱策略禁止外部网络访问: " + toolCall.getToolCode();
        }
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && "sys.bash".equalsIgnoreCase(toolCall.getToolCode())) {
            return "STRICT 沙箱禁止 sys.bash";
        }
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && "code.exec".equalsIgnoreCase(toolCall.getToolCode())) {
            return "STRICT 沙箱禁止 code.exec";
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
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && "code.exec".equalsIgnoreCase(toolCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "STRICT 沙箱禁止 code.exec");
        }
        if (!policy.isNetworkEgressEnabled() && isNetworkBuiltinTool(toolCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前沙箱策略禁止外部网络访问");
        }
        if (workingDirectory != null && !CollectionUtils.isEmpty(policy.getAllowedPathPrefixes())) {
            boolean allowed = policy.getAllowedPathPrefixes().stream()
                    .anyMatch(prefix -> workingDirectory.normalize().startsWith(prefix.normalize()));
            if (!allowed) {
                throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作目录超出沙箱允许范围");
            }
        }
        if ("sys.bash".equalsIgnoreCase(toolCode) && StringUtils.hasText(command)) {
            String violation = checkCommand(command, policy);
            if (StringUtils.hasText(violation)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, violation);
            }
        }
    }

    public String checkCommand(String command, SandboxPolicy policy) {
        if (!StringUtils.hasText(command)) {
            return "bash 命令不能为空";
        }
        if (policy != null && policy.getSandboxProfile() == SandboxProfileEnum.STRICT) {
            return "STRICT 沙箱禁止所有 bash 命令";
        }

        Set<String> allowedCommands = resolveAllowedCommands(policy);
        List<String> segments = splitCommandSegments(command);
        if (segments.isEmpty()) {
            return "无法识别 bash 命令";
        }
        for (String segment : segments) {
            String baseCommand = extractBaseCommand(segment);
            if (!StringUtils.hasText(baseCommand)) {
                return "无法识别 bash 命令";
            }
            if (BLOCKED_SHELL_PREFIXES.contains(baseCommand)) {
                return "禁止使用提权命令: " + baseCommand;
            }
            if (!allowedCommands.contains(baseCommand)) {
                return "命令 [" + baseCommand + "] 不在白名单中";
            }
        }
        if (containsInjectionPattern(command, allowedCommands)) {
            return "检测到潜在命令注入模式";
        }
        return null;
    }

    String extractBaseCommand(String command) {
        if (!StringUtils.hasText(command)) {
            return null;
        }
        String[] tokens = command.strip().split("\\s+");
        int index = 0;
        while (index < tokens.length && ENV_ASSIGNMENT_PATTERN.matcher(tokens[index]).matches()) {
            index++;
        }
        if (index >= tokens.length) {
            return null;
        }
        return tokens[index].trim().toLowerCase();
    }

    boolean containsInjectionPattern(String command, Set<String> allowedCommands) {
        if (!StringUtils.hasText(command)) {
            return false;
        }
        if (SHELL_SUBSTITUTION_PATTERN.matcher(command).find()) {
            return true;
        }
        if (REDIRECTION_PATTERN.matcher(command).find()) {
            return true;
        }
        List<String> segments = splitCommandSegments(command);
        if (segments.size() <= 1) {
            return false;
        }
        for (int index = 1; index < segments.size(); index++) {
            String chainedBaseCommand = extractBaseCommand(segments.get(index));
            if (!StringUtils.hasText(chainedBaseCommand)
                    || BLOCKED_SHELL_PREFIXES.contains(chainedBaseCommand)
                    || !allowedCommands.contains(chainedBaseCommand)) {
                return true;
            }
        }
        return false;
    }

    private boolean isNetworkBuiltinTool(String toolCode) {
        return "web.fetch".equalsIgnoreCase(toolCode);
    }

    private Set<String> resolveAllowedCommands(SandboxPolicy policy) {
        if (policy == null || policy.getAllowedCommands() == null) {
            return configuredAllowedCommands;
        }
        return policy.getAllowedCommands();
    }

    private List<String> splitCommandSegments(String command) {
        List<String> result = new ArrayList<>();
        for (String segment : COMMAND_CHAIN_SPLITTER.split(command)) {
            if (StringUtils.hasText(segment)) {
                result.add(segment.trim());
            }
        }
        return result;
    }

    private Set<String> parseAllowedCommands(String configValue) {
        if (!StringUtils.hasText(configValue)) {
            return Set.of();
        }
        return List.of(configValue.split(",")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                        Set::copyOf
                ));
    }
}
