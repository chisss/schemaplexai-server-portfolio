package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import jakarta.annotation.PostConstruct;
import com.schemaplexai.service.security.SecurityAuditEventService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 沙箱守卫。 */
@Slf4j
@Component
public class SandboxGuard {

    private static final Pattern ENV_ASSIGNMENT_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=\\S+$");
    private static final Pattern SHELL_SUBSTITUTION_PATTERN = Pattern.compile("`|\\$\\(");
    private static final Pattern REDIRECTION_PATTERN = Pattern.compile("(^|\\s)(\\d*>>?|<<?|&>)");
    private static final Pattern SHELL_TOKEN_PATTERN = Pattern.compile(
            "'[^']*+'|\"[^\"\\\\]*+(?:\\\\[\\s\\S][^\"\\\\]*+)*+\"|\\\\[^\\r\\n]|([|;&\\r\\n])");
    private static final Set<String> WORKSPACE_WRITES = Set.of(
            "sys.write", "sys.edit", "sys.edit.exact", "sys.mkdir", "sys.rm", "sys.cp", "sys.mv");
    private static final Set<String> BLOCKED_SHELL_PREFIXES = Set.of("sudo", "su");

    @Value("${schemaplexai.sandbox.allowed-commands:ls,cat,head,tail,grep,find,wc,sort,uniq,diff,echo,pwd,date,whoami}")
    private String allowedCommandsConfig;

    /**
     * 逃生阀：策略缺失时是否放行（默认 false = fail-closed，仅运维应急可开启）
     */
    @Value("${schemaplexai.sandbox.fail-open-on-null:false}")
    private boolean failOpenOnNull;

    private SecurityAuditEventService securityAuditEventService;

    @Autowired(required = false)
    void setSecurityAuditEventService(SecurityAuditEventService securityAuditEventService) {
        this.securityAuditEventService = securityAuditEventService;
    }

    private Set<String> configuredAllowedCommands = Set.of();

    @PostConstruct
    void init() {
        configuredAllowedCommands = parseAllowedCommands(allowedCommandsConfig);
    }

    /**
     * 校验工具调用（sourceType 从 binding 派生）。
     * 策略缺失时默认拒绝（fail-closed），可用 {@code schemaplexai.sandbox.fail-open-on-null} 逃生阀回退旧行为。
     */
    public String validateTool(SandboxPolicy policy, AgentToolBinding binding, ToolCall toolCall) {
        String sourceType = binding != null && StringUtils.hasText(binding.getSourceType())
                ? binding.getSourceType().trim().toLowerCase()
                : SourceTypeEnum.BUILTIN.getCode();
        return validateTool(policy, sourceType, toolCall, binding != null ? binding.getTenantId() : null);
    }

    /**
     * 校验工具调用（显式 sourceType，供无 AgentToolBinding 的 MCP/Skill 原生工具使用）。
     */
    public String validateTool(SandboxPolicy policy, String sourceType, ToolCall toolCall) {
        return validateTool(policy, sourceType, toolCall, policy == null ? null : policy.getTenantId());
    }

    public String validateTool(SandboxPolicy policy, String sourceType, ToolCall toolCall, String tenantId) {
        if (toolCall == null || !StringUtils.hasText(toolCall.getToolCode())) {
            return null;
        }
        if (policy == null) {
            if (!failOpenOnNull) {
                return "沙箱策略缺失，工具执行被拒绝（fail-closed）";
            }
            auditFailOpen(tenantId, toolCall.getToolCode());
            return null;
        }
        Set<String> allowedToolCodes = policy.getAllowedToolCodes();
        if (!CollectionUtils.isEmpty(allowedToolCodes) && !allowedToolCodes.contains(toolCall.getToolCode())) {
            return "工具未在沙箱白名单内: " + toolCall.getToolCode();
        }
        String effectiveSourceType = StringUtils.hasText(sourceType)
                ? sourceType.trim().toLowerCase()
                : SourceTypeEnum.BUILTIN.getCode();
        if (!policy.isNetworkEgressEnabled() && !SourceTypeEnum.BUILTIN.getCode().equals(effectiveSourceType)) {
            return "当前沙箱策略禁止外部网络工具: " + toolCall.getToolCode();
        }
        if (!policy.isNetworkEgressEnabled() && isNetworkBuiltinTool(toolCall.getToolCode())) {
            return "当前沙箱策略禁止外部网络访问: " + toolCall.getToolCode();
        }
        String canonicalCode = toolCall.getToolCode().replace('_', '.').toLowerCase(Locale.ROOT);
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT
                && WORKSPACE_WRITES.contains(canonicalCode)) {
            return "STRICT 沙箱禁止修改工作区文件";
        }
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && "sys.bash".equals(canonicalCode)) {
            return "STRICT 沙箱禁止 sys.bash";
        }
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && "code.exec".equals(canonicalCode)) {
            return "STRICT 沙箱禁止 code.exec";
        }
        return null;
    }

    public void validateBuiltinExecution(SandboxPolicy policy, String toolCode, Map<String, Object> args, Path workingDirectory, String command) {
        if (policy == null) {
            if (!failOpenOnNull) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "沙箱策略缺失，禁止执行（fail-closed）");
            }
            auditFailOpen(null, toolCode);
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
        if (workingDirectory != null) {
            validateWorkspacePaths(policy, args, workingDirectory);
        }
        if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT && WORKSPACE_WRITES.contains(toolCode)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "STRICT 沙箱禁止修改工作区文件");
        }
        if ("sys.bash".equalsIgnoreCase(toolCode) && StringUtils.hasText(command)) {
            String violation = checkCommand(command, policy);
            if (StringUtils.hasText(violation)) {
                throw new BusinessException(ResultCode.BAD_REQUEST, violation);
            }
        }
    }

    private void validateWorkspacePaths(SandboxPolicy policy, Map<String, Object> args, Path directory) {
        if (CollectionUtils.isEmpty(policy.getAllowedPathPrefixes())) {
            if (policy.getSandboxProfile() == SandboxProfileEnum.STRICT) {
                throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "STRICT 沙箱需要明确的工作区路径");
            }
            return;
        }
        List<Path> paths = new ArrayList<>();
        paths.add(directory);
        if (args != null) {
            for (String field : List.of("path", "source", "target")) {
                if (args.get(field) instanceof String path && StringUtils.hasText(path)) {
                    paths.add(directory.resolve(path));
                }
            }
        }
        boolean allowed = paths.stream().allMatch(path -> policy.getAllowedPathPrefixes().stream()
                .anyMatch(prefix -> resolvesWithin(path, prefix)));
        if (!allowed) {
            throw new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "实际文件路径超出沙箱允许范围");
        }
    }

    private boolean resolvesWithin(Path path, Path prefix) {
        Path root = prefix.toAbsolutePath().normalize();
        Path candidate = path.toAbsolutePath().normalize();
        if (!candidate.startsWith(root) || !Files.isDirectory(root)) return false;
        try {
            Path existing = candidate;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            return existing != null && existing.toRealPath().startsWith(root.toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    public String checkCommand(String command, SandboxPolicy policy) {
        if (!StringUtils.hasText(command)) {
            return "bash 命令不能为空";
        }
        if (policy != null && policy.getSandboxProfile() == SandboxProfileEnum.STRICT) {
            return "STRICT 沙箱禁止所有 bash 命令";
        }

        if (SHELL_SUBSTITUTION_PATTERN.matcher(command).find() || REDIRECTION_PATTERN.matcher(command).find()) {
            return "检测到潜在命令注入模式";
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
            String executable = baseCommand.substring(baseCommand.lastIndexOf('/') + 1);
            if (BLOCKED_SHELL_PREFIXES.contains(executable)) {
                return "禁止使用提权命令: " + baseCommand;
            }
            if (!allowedCommands.contains(baseCommand)) {
                return "命令 [" + baseCommand + "] 不在白名单中";
            }
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

    private void auditFailOpen(String tenantId, String toolCode) {
        log.warn("sandbox_fail_open tenantId={}, toolCode={}", tenantId, toolCode);
        if (securityAuditEventService == null || !StringUtils.hasText(tenantId)) {
            return;
        }
        try {
            securityAuditEventService.recordEvent(tenantId, null, "SANDBOX_FAIL_OPEN", "sandbox",
                    "allowed", "high", "runtime", null, null, "tool", null,
                    "沙箱策略缺失时放行", null, Map.of("toolCode", toolCode), null);
        } catch (RuntimeException e) {
            log.error("写入沙箱审计事件失败: tenantId={}, toolCode={}", tenantId, toolCode, e);
        }
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
        Matcher matcher = SHELL_TOKEN_PATTERN.matcher(command);
        int start = 0;
        while (matcher.find()) {
            if (matcher.group(1) == null) {
                continue;
            }
            addCommandSegment(result, command.substring(start, matcher.start()));
            start = matcher.end();
        }
        addCommandSegment(result, command.substring(start));
        return result;
    }

    private void addCommandSegment(List<String> segments, String segment) {
        if (StringUtils.hasText(segment)) {
            segments.add(segment.trim());
        }
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
