package com.schemaplexai.service.agent.tool.executor;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.schemaplexai.common.enums.SourceTypeEnum;
import com.schemaplexai.common.enums.ToolExecutionStatusEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionErrorCode;
import com.schemaplexai.service.agent.tool.audit.ToolExecutionLogService;
import com.schemaplexai.service.agent.tool.executor.os.CommandValidator;
import com.schemaplexai.service.agent.tool.executor.os.ShellCommandAdapter;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.service.agent.tool.sandbox.CodeExecRequest;
import com.schemaplexai.service.agent.tool.sandbox.CodeExecResult;
import com.schemaplexai.service.agent.tool.sandbox.WasmSandboxService;
import com.schemaplexai.service.ai.ImageGenerationService;
import com.schemaplexai.service.tool.security.ToolSecurityValidator;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import okhttp3.HttpUrl;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系统内置工具执行器
 */
@Slf4j
@Component
public class BuiltinToolExecutor implements ToolExecutor {

    private static final Set<String> SUPPORTED_CODES = Set.of(
            "sys.read", "sys.write", "sys.edit", "sys.bash", "sys.glob", "sys.grep",
            "sys.ls", "sys.mkdir", "sys.rm", "sys.cp", "sys.mv", "sys.stat",
            "web.fetch", "code.exec", "ai.image.generate"
    );

    private static final Cache<String, ToolIoTypeEnum> IO_TYPE_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(100)
            .build();

    private static final long PROCESS_TIMEOUT_SECONDS = 30;
    private static final int MAX_COMMAND_OUTPUT_LENGTH = 24000;
    private static final int DEFAULT_FETCH_MAX_CHARS = 12000;
    private static final int MAX_FETCH_MAX_CHARS = 40000;
    private static final long MAX_FETCH_PEEK_BYTES = 256 * 1024L;
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern HTML_LINK_PATTERN = Pattern.compile(
            "(?is)<a\\b[^>]*href\\s*=\\s*(['\"])(.*?)\\1[^>]*>(.*?)</a>"
    );
    private static final Pattern HTML_EMAIL_PATTERN = Pattern.compile(
            "(?i)\\b([a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,})\\b"
    );
    private static final Pattern HTML_PHONE_PATTERN = Pattern.compile(
            "(?<!\\w)(\\+?\\d[\\d\\s().\\-/]{6,}\\d)(?!\\w)"
    );
    private static final int MAX_FETCH_LINKS = 12;
    private static final int MAX_FETCH_CONTACTS = 10;
    private static final List<String> PRIORITY_LINK_KEYWORDS = List.of(
            "contact", "contato", "contacto", "fale", "atendimento", "support",
            "sales", "purchase", "procurement", "compras", "distribuidor", "distributor",
            "amino", "aminoacid", "amino-acid", "aminoacido", "aminoacidos",
            "bcaa", "eaa", "glutamine", "glutamina", "products", "product", "produto",
            "produtos", "shop", "store", "tienda", "suplement", "suplemento", "supplement"
    );
    private static final List<String> LOW_PRIORITY_LINK_KEYWORDS = List.of(
            "privacy", "terms", "policy", "login", "register", "cart", "checkout",
            "instagram", "facebook", "linkedin", "youtube", "whatsapp", "mailto:", "tel:"
    );
    private static final String COMMAND_OUTPUT_TRUNCATION_NOTICE =
            "\n...[命令输出过长，已截断。请缩小范围、增加过滤条件或分批读取]";
    private static final String DEFAULT_FETCH_USER_AGENT =
            "SchemaPlexAI-WebFetch/1.0 (+https://schemaplexai.local)";
    private static final String DEFAULT_ACCEPT_LANGUAGE = "zh-CN,zh;q=0.9,en;q=0.8";

    private final ObjectMapper objectMapper;
    private final List<ShellCommandAdapter> adapters;
    private final CommandValidator commandValidator;
    private final ToolExecutionLogService logService;
    private final WorkspacePathResolver workspacePathResolver;
    private final SandboxGuard sandboxGuard;
    private final OkHttpClient httpClient;
    private final ToolSecurityValidator toolSecurityValidator;
    private final WasmSandboxService wasmSandboxService;
    private final ToolExecutionLockService toolExecutionLockService;
    private final BuiltinToolMapper builtinToolMapper;
    private final ImageGenerationService imageGenerationService;

    @Autowired
    public BuiltinToolExecutor(ObjectMapper objectMapper,
                               List<ShellCommandAdapter> adapters,
                               CommandValidator commandValidator,
                               ToolExecutionLogService logService,
                               WorkspacePathResolver workspacePathResolver,
                               SandboxGuard sandboxGuard,
                               OkHttpClient httpClient,
                               ToolSecurityValidator toolSecurityValidator,
                               WasmSandboxService wasmSandboxService,
                               ToolExecutionLockService toolExecutionLockService,
                               BuiltinToolMapper builtinToolMapper,
                               ImageGenerationService imageGenerationService) {
        this.objectMapper = objectMapper;
        this.adapters = adapters;
        this.commandValidator = commandValidator;
        this.logService = logService;
        this.workspacePathResolver = workspacePathResolver;
        this.sandboxGuard = sandboxGuard;
        this.httpClient = httpClient;
        this.toolSecurityValidator = toolSecurityValidator;
        this.wasmSandboxService = wasmSandboxService;
        this.toolExecutionLockService = toolExecutionLockService;
        this.builtinToolMapper = builtinToolMapper;
        this.imageGenerationService = imageGenerationService;
    }

    public BuiltinToolExecutor(ObjectMapper objectMapper,
                               List<ShellCommandAdapter> adapters,
                               CommandValidator commandValidator,
                               ToolExecutionLogService logService,
                               WorkspacePathResolver workspacePathResolver,
                               SandboxGuard sandboxGuard,
                               OkHttpClient httpClient,
                               ToolSecurityValidator toolSecurityValidator,
                               WasmSandboxService wasmSandboxService,
                               ToolExecutionLockService toolExecutionLockService,
                               BuiltinToolMapper builtinToolMapper) {
        this(objectMapper, adapters, commandValidator, logService, workspacePathResolver, sandboxGuard,
                httpClient, toolSecurityValidator, wasmSandboxService, toolExecutionLockService,
                builtinToolMapper, null);
    }

    @Override
    public String sourceType() {
        return SourceTypeEnum.BUILTIN.getCode();
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall) {
        return execute(tenantId, agentId, binding, toolCall, null);
    }

    @Override
    public ToolResult execute(String tenantId, String agentId, AgentToolBinding binding, ToolCall toolCall, SandboxPolicy sandboxPolicy) {
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
                args = new LinkedHashMap<>();
            } else {
                args = new LinkedHashMap<>(args);
            }
            prepareWorkingDirectory(toolCode, args, sandboxPolicy);
            Map<String, Object> safeArgsForLog = sanitizeArgsForLog(args);
            if ("web.fetch".equals(toolCode)) {
                return executeWebFetch(tenantId, agentId, toolCall, sandboxPolicy, startAt, args, safeArgsForLog);
            }
            if ("code.exec".equals(toolCode)) {
                return executeCodeExec(tenantId, agentId, toolCall, sandboxPolicy, startAt, args, safeArgsForLog);
            }
            if ("ai.image.generate".equals(toolCode)) {
                return executeImageGenerate(tenantId, agentId, toolCall, startAt, args, safeArgsForLog);
            }
            if (!commandValidator.validateToolArguments(toolCode, args)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), safeArgsForLog, null,
                        ToolExecutionErrorCode.INVALID_ARGUMENT, "系统工具参数校验失败");
                return failure(toolCall, "系统工具参数校验失败");
            }
            Path workingDirectory = resolveWorkingDirectory(toolCode, args, sandboxPolicy);
            if (requiresWorkingDirectory(toolCode) && workingDirectory == null) {
                String error = "系统工具调用缺少 workdir";
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), safeArgsForLog, null,
                        ToolExecutionErrorCode.MISSING_WORKDIR, error);
                return failure(toolCall, error);
            }
            ToolResult missingPathResult = validateReadablePathExists(toolCode, toolCall, args, workingDirectory,
                    startAt, safeArgsForLog, tenantId, agentId);
            if (missingPathResult != null) {
                return missingPathResult;
            }

            String command = adapter.adaptCommand(toolCode, args);

            if (!commandValidator.isCommandAllowed(toolCode, command)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), safeArgsForLog, null,
                        ToolExecutionErrorCode.SANDBOX_VIOLATION, "命令不在白名单");
                return failure(toolCall, "命令不在白名单");
            }

            ProcessBuilder processBuilder = new ProcessBuilder(adapter.wrapShellCommand(command));
            sandboxGuard.validateBuiltinExecution(sandboxPolicy, toolCode, args, workingDirectory, command);
            if (workingDirectory != null) {
                processBuilder.directory(workingDirectory.toFile());
            }
            processBuilder.redirectErrorStream(true);

            // 并行执行保护：写操作加写锁，读操作加读锁
            ToolExecutionLockService.LockAction<ToolResult> processAction = () ->
                    executeProcess(processBuilder, toolCall, toolCode, startAt, safeArgsForLog, tenantId, agentId);
            if (workingDirectory != null) {
                ToolIoTypeEnum ioType = resolveIoType(toolCode);
                if (ioType.isWrite()) {
                    return toolExecutionLockService.executeWithWriteLock(workingDirectory, processAction);
                }
                if (ioType == ToolIoTypeEnum.READ) {
                    return toolExecutionLockService.executeWithReadLock(workingDirectory, processAction);
                }
            }
            return processAction.execute();
        } catch (Exception e) {
            log.error("系统工具执行失败: toolCode={}, error={}", toolCode, e.getMessage());
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.ERROR.getCode(), startAt, LocalDateTime.now(), null, null,
                    ToolExecutionErrorCode.UNKNOWN_ERROR, e.getMessage());
            return failure(toolCall, "执行失败: " + e.getMessage());
        }
    }

    private ToolResult executeWebFetch(String tenantId,
                                       String agentId,
                                       ToolCall toolCall,
                                       SandboxPolicy sandboxPolicy,
                                       LocalDateTime startAt,
                                       Map<String, Object> args,
                                       Map<String, Object> safeArgsForLog) {
        String url = asString(args.get("url"));
        if (!StringUtils.hasText(url)) {
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCall.getToolCode(), ToolExecutionStatusEnum.FAILED.getCode(),
                    startAt, LocalDateTime.now(), safeArgsForLog, null, "web.fetch 缺少 url");
            return failure(toolCall, "web.fetch 缺少 url");
        }
        if (url.length() > 2000) {
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCall.getToolCode(), ToolExecutionStatusEnum.FAILED.getCode(),
                    startAt, LocalDateTime.now(), safeArgsForLog, null, "web.fetch url 超长");
            return failure(toolCall, "web.fetch url 超长");
        }
        try {
            sandboxGuard.validateBuiltinExecution(sandboxPolicy, toolCall.getToolCode(), args, null, null);
            toolSecurityValidator.validateUrl(url);

            int maxChars = clampFetchMaxChars(args.get("maxChars"));
            boolean includeHtml = readBoolean(args.get("includeHtml"));
            String userAgent = StringUtils.hasText(asString(args.get("userAgent")))
                    ? asString(args.get("userAgent")) : DEFAULT_FETCH_USER_AGENT;
            String acceptLanguage = StringUtils.hasText(asString(args.get("acceptLanguage")))
                    ? asString(args.get("acceptLanguage")) : DEFAULT_ACCEPT_LANGUAGE;

            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", acceptLanguage)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String contentType = response.body() != null && response.body().contentType() != null
                        ? response.body().contentType().toString() : null;
                String rawBody = response.peekBody(Math.min(MAX_FETCH_PEEK_BYTES, Math.max(maxChars * 8L, 32 * 1024L))).string();
                String content = isHtmlContent(contentType, rawBody)
                        ? extractHtmlText(rawBody, maxChars)
                        : truncateFetchContent(rawBody, maxChars);

                Map<String, Object> result = new LinkedHashMap<>();
                result.put("finalUrl", response.request().url().toString());
                result.put("statusCode", response.code());
                result.put("contentType", contentType);
                String title = extractHtmlTitle(rawBody);
                if (StringUtils.hasText(title)) {
                    result.put("title", title);
                }
                result.put("content", content);
                if (isHtmlContent(contentType, rawBody)) {
                    List<Map<String, String>> links = extractHtmlLinks(rawBody, response.request().url(), MAX_FETCH_LINKS);
                    if (!links.isEmpty()) {
                        result.put("links", links);
                    }
                    List<String> emails = extractEmails(rawBody, MAX_FETCH_CONTACTS);
                    if (!emails.isEmpty()) {
                        result.put("emails", emails);
                    }
                    List<String> phones = extractPhones(rawBody, MAX_FETCH_CONTACTS);
                    if (!phones.isEmpty()) {
                        result.put("phones", phones);
                    }
                }
                if (includeHtml && StringUtils.hasText(rawBody)) {
                    result.put("html", truncateFetchContent(rawBody, maxChars));
                }

                LocalDateTime endAt = LocalDateTime.now();
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), toolCall.getToolCode(),
                        response.isSuccessful() ? ToolExecutionStatusEnum.SUCCESS.getCode() : ToolExecutionStatusEnum.FAILED.getCode(),
                        startAt, endAt, safeArgsForLog, result, null);
                return ToolResult.builder()
                        .callId(toolCall.getCallId())
                        .toolCode(toolCall.getToolCode())
                        .success(response.isSuccessful())
                        .result(objectMapper.valueToTree(result))
                        .errorMessage(response.isSuccessful() ? null : "HTTP 请求失败: " + response.code())
                        .build();
            }
        } catch (Exception exception) {
            log.error("web.fetch 执行失败: url={}, error={}", url, exception.getMessage());
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCall.getToolCode(), ToolExecutionStatusEnum.ERROR.getCode(),
                    startAt, LocalDateTime.now(), safeArgsForLog, null, exception.getMessage());
            return failure(toolCall, "执行失败: " + exception.getMessage());
        }
    }

    private ToolResult executeImageGenerate(String tenantId,
                                            String agentId,
                                            ToolCall toolCall,
                                            LocalDateTime startAt,
                                            Map<String, Object> args,
                                            Map<String, Object> safeArgsForLog) {
        try {
            ImageGenerationService.ImageGenerationRequest request = new ImageGenerationService.ImageGenerationRequest();
            request.setModelId(asString(args.get("modelId")));
            request.setPrompt(asString(args.get("prompt")));
            request.setSize(asString(args.get("size")));
            request.setQuality(asString(args.get("quality")));
            Object n = args.get("n");
            if (n instanceof Number number) {
                request.setN(number.intValue());
            } else if (n != null && StringUtils.hasText(String.valueOf(n))) {
                request.setN(Integer.parseInt(String.valueOf(n)));
            }
            ImageGenerationService.ImageGenerationResult result = imageGenerationService.generate(request);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("modelId", result.getModelId());
            payload.put("modelName", result.getModelName());
            payload.put("prompt", result.getPrompt());
            payload.put("size", result.getSize());
            payload.put("quality", result.getQuality());
            payload.put("imageUrls", result.getImageUrls());
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCall.getToolCode(), ToolExecutionStatusEnum.SUCCESS.getCode(),
                    startAt, LocalDateTime.now(), safeArgsForLog, payload, null);
            return ToolResult.builder()
                    .callId(toolCall.getCallId())
                    .toolCode(toolCall.getToolCode())
                    .success(true)
                    .result(objectMapper.valueToTree(payload))
                    .build();
        } catch (Exception exception) {
            String error = "生图工具执行失败: " + exception.getMessage();
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), toolCall.getToolCode(), ToolExecutionStatusEnum.ERROR.getCode(),
                    startAt, LocalDateTime.now(), safeArgsForLog, null, error);
            return failure(toolCall, error);
        }
    }

    /**
     * 在 Wasm 沙箱中执行代码（code.exec）
     * <p>基于 Chicory + QuickJs4j，代码在 Wasm 双重隔离环境中运行，
     * 无法访问文件系统、网络或操作系统资源。
     */
    private ToolResult executeCodeExec(String tenantId,
                                       String agentId,
                                       ToolCall toolCall,
                                       SandboxPolicy sandboxPolicy,
                                       LocalDateTime startAt,
                                       Map<String, Object> args,
                                       Map<String, Object> safeArgsForLog) {
        try {
            String language = asString(args.get("language"));
            String code = asString(args.get("code"));
            int timeout = 0;
            Object timeoutArg = args.get("timeout");
            if (timeoutArg instanceof Number) {
                timeout = ((Number) timeoutArg).intValue();
            }

            if (!StringUtils.hasText(code)) {
                logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                        SourceTypeEnum.BUILTIN.getCode(), "code.exec", ToolExecutionStatusEnum.FAILED.getCode(),
                        startAt, LocalDateTime.now(), safeArgsForLog, null, "代码参数不能为空");
                return failure(toolCall, "代码参数不能为空");
            }

            // 沙箱策略校验
            sandboxGuard.validateBuiltinExecution(sandboxPolicy, "code.exec", args, null, null);

            // 构建并执行
            CodeExecRequest request = CodeExecRequest.builder()
                    .language(StringUtils.hasText(language) ? language : "javascript")
                    .code(code)
                    .timeoutMs(timeout)
                    .build();

            CodeExecResult execResult = wasmSandboxService.execute(request);
            LocalDateTime endAt = LocalDateTime.now();

            // 构建返回结果
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("stdout", execResult.getStdout());
            resultMap.put("stderr", execResult.getStderr());
            resultMap.put("exitCode", execResult.getExitCode());
            resultMap.put("executionMs", execResult.getExecutionMs());
            resultMap.put("language", execResult.getLanguage());

            boolean success = execResult.getExitCode() == 0;
            String status = success ? ToolExecutionStatusEnum.SUCCESS.getCode() : ToolExecutionStatusEnum.FAILED.getCode();
            String errorMsg = success ? null : execResult.getStderr();

            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), "code.exec", status,
                    startAt, endAt, safeArgsForLog, resultMap, errorMsg);

            return ToolResult.builder()
                    .callId(toolCall.getCallId())
                    .toolCode("code.exec")
                    .success(success)
                    .result(objectMapper.valueToTree(resultMap))
                    .errorMessage(errorMsg)
                    .build();
        } catch (Exception exception) {
            log.error("代码沙箱执行异常: {}", exception.getMessage());
            logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                    SourceTypeEnum.BUILTIN.getCode(), "code.exec", ToolExecutionStatusEnum.ERROR.getCode(),
                    startAt, LocalDateTime.now(), safeArgsForLog, null, exception.getMessage());
            return failure(toolCall, "代码执行失败: " + exception.getMessage());
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

    private String extractHtmlTitle(String html) {
        if (!StringUtils.hasText(html)) {
            return null;
        }
        Matcher matcher = HTML_TITLE_PATTERN.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        return truncateFetchContent(HtmlUtils.htmlUnescape(matcher.group(1)).trim(), 256);
    }

    private String extractHtmlText(String html, int maxChars) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        String sanitized = sanitizeHtmlToText(html);
        return truncateFetchContent(sanitized, maxChars);
    }

    private String sanitizeHtmlToText(String html) {
        String sanitized = html
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("(?i)</div\\s*>", "\n")
                .replaceAll("(?i)</li\\s*>", "\n")
                .replaceAll("(?is)<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(sanitized)
                .replace('\u00A0', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll(" *\\n *", "\n")
                .trim();
    }

    private List<Map<String, String>> extractHtmlLinks(String html, HttpUrl baseUrl, int limit) {
        if (!StringUtils.hasText(html) || baseUrl == null || limit <= 0) {
            return List.of();
        }
        Matcher matcher = HTML_LINK_PATTERN.matcher(html);
        Map<String, LinkCandidate> deduplicated = new LinkedHashMap<>();
        while (matcher.find()) {
            String href = matcher.group(2);
            if (!StringUtils.hasText(href)) {
                continue;
            }
            String normalizedHref = href.trim();
            if (normalizedHref.startsWith("#")
                    || normalizedHref.startsWith("mailto:")
                    || normalizedHref.startsWith("tel:")
                    || normalizedHref.startsWith("javascript:")) {
                continue;
            }
            HttpUrl resolved = baseUrl.resolve(normalizedHref);
            if (resolved == null || !"http".equalsIgnoreCase(resolved.scheme()) && !"https".equalsIgnoreCase(resolved.scheme())) {
                continue;
            }
            if (!isSameSite(baseUrl, resolved)) {
                continue;
            }
            String absoluteUrl = resolved.newBuilder().fragment(null).build().toString();
            String anchorText = sanitizeHtmlToText(matcher.group(3));
            LinkCandidate candidate = new LinkCandidate(absoluteUrl, truncateFetchContent(anchorText, 80),
                    computeLinkPriority(baseUrl, absoluteUrl, anchorText));
            LinkCandidate existing = deduplicated.get(absoluteUrl);
            if (existing == null || candidate.priority() > existing.priority()) {
                deduplicated.put(absoluteUrl, candidate);
            }
        }
        return deduplicated.values().stream()
                .sorted(Comparator
                        .comparingInt(LinkCandidate::priority).reversed()
                        .thenComparing(LinkCandidate::url))
                .limit(limit)
                .map(candidate -> {
                    Map<String, String> value = new LinkedHashMap<>();
                    value.put("url", candidate.url());
                    if (StringUtils.hasText(candidate.text())) {
                        value.put("text", candidate.text());
                    }
                    return value;
                })
                .toList();
    }

    private boolean isSameSite(HttpUrl baseUrl, HttpUrl resolved) {
        String baseHost = baseUrl.host();
        String resolvedHost = resolved.host();
        if (!StringUtils.hasText(baseHost) || !StringUtils.hasText(resolvedHost)) {
            return false;
        }
        return baseHost.equalsIgnoreCase(resolvedHost)
                || resolvedHost.toLowerCase().endsWith("." + baseHost.toLowerCase())
                || baseHost.toLowerCase().endsWith("." + resolvedHost.toLowerCase());
    }

    private int computeLinkPriority(HttpUrl baseUrl, String absoluteUrl, String anchorText) {
        String normalized = (absoluteUrl + " " + anchorText).toLowerCase();
        int score = 0;
        for (String keyword : PRIORITY_LINK_KEYWORDS) {
            if (normalized.contains(keyword)) {
                score += 10;
            }
        }
        for (String keyword : LOW_PRIORITY_LINK_KEYWORDS) {
            if (normalized.contains(keyword)) {
                score -= 8;
            }
        }
        if (absoluteUrl.startsWith(baseUrl.scheme() + "://" + baseUrl.host() + "/")) {
            score += 4;
        }
        if (absoluteUrl.equals(baseUrl.toString()) || absoluteUrl.equals(baseUrl.newBuilder().fragment(null).build().toString())) {
            score -= 4;
        }
        return score;
    }

    private List<String> extractEmails(String html, int limit) {
        if (!StringUtils.hasText(html) || limit <= 0) {
            return List.of();
        }
        Matcher matcher = HTML_EMAIL_PATTERN.matcher(HtmlUtils.htmlUnescape(html));
        Set<String> emails = new LinkedHashSet<>();
        while (matcher.find() && emails.size() < limit) {
            String email = matcher.group(1);
            if (StringUtils.hasText(email)) {
                emails.add(email.toLowerCase());
            }
        }
        return List.copyOf(emails);
    }

    private List<String> extractPhones(String html, int limit) {
        if (!StringUtils.hasText(html) || limit <= 0) {
            return List.of();
        }
        Matcher matcher = HTML_PHONE_PATTERN.matcher(sanitizeHtmlToText(html));
        List<String> phones = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        while (matcher.find() && phones.size() < limit) {
            String rawPhone = matcher.group(1);
            if (!StringUtils.hasText(rawPhone)) {
                continue;
            }
            String normalized = rawPhone.replaceAll("\\s+", " ").trim();
            String digits = normalized.replaceAll("\\D", "");
            if (digits.length() < 7 || digits.length() > 16) {
                continue;
            }
            if (seen.add(normalized)) {
                phones.add(normalized);
            }
        }
        return phones;
    }

    private boolean isHtmlContent(String contentType, String rawBody) {
        if (StringUtils.hasText(contentType) && contentType.toLowerCase().contains("html")) {
            return true;
        }
        return StringUtils.hasText(rawBody) && rawBody.contains("<html");
    }

    private int clampFetchMaxChars(Object value) {
        if (value == null) {
            return DEFAULT_FETCH_MAX_CHARS;
        }
        try {
            int parsed = Integer.parseInt(String.valueOf(value));
            if (parsed <= 0) {
                return DEFAULT_FETCH_MAX_CHARS;
            }
            return Math.min(parsed, MAX_FETCH_MAX_CHARS);
        } catch (NumberFormatException exception) {
            return DEFAULT_FETCH_MAX_CHARS;
        }
    }

    private boolean readBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private String truncateFetchContent(String content, int maxChars) {
        if (!StringUtils.hasText(content) || content.length() <= maxChars) {
            return content;
        }
        return content.substring(0, Math.max(maxChars, 1));
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private String truncateCommandOutput(String output) {
        if (!StringUtils.hasText(output) || output.length() <= MAX_COMMAND_OUTPUT_LENGTH) {
            return output;
        }
        int maxLength = Math.max(MAX_COMMAND_OUTPUT_LENGTH - COMMAND_OUTPUT_TRUNCATION_NOTICE.length(), 1);
        return output.substring(0, maxLength) + COMMAND_OUTPUT_TRUNCATION_NOTICE;
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
        // code.exec 的代码字段截断（避免日志过大）
        if (copy.containsKey("code") && copy.get("code") instanceof String codeStr && codeStr.length() > 200) {
            copy.put("code", codeStr.substring(0, 200) + "...[截断]");
        }
        return copy;
    }

    private void prepareWorkingDirectory(String toolCode, Map<String, Object> args, SandboxPolicy sandboxPolicy) {
        if (!requiresWorkingDirectory(toolCode) || args == null || sandboxPolicy == null) {
            return;
        }
        String workdir = asString(args.get("workdir"));
        if (!StringUtils.hasText(workdir)) {
            Path defaultWorkingDirectory = sandboxPolicy.getDefaultWorkingDirectory();
            if (defaultWorkingDirectory != null) {
                args.put("workdir", defaultWorkingDirectory.toString());
            }
            return;
        }
        Path translated = translateWorkspaceAlias(Path.of(workdir).toAbsolutePath().normalize(), sandboxPolicy);
        args.put("workdir", translated.toString());
    }

    private Path resolveWorkingDirectory(String toolCode, Map<String, Object> args, SandboxPolicy sandboxPolicy) {
        if (!requiresWorkingDirectory(toolCode) || args == null || !args.containsKey("workdir")) {
            return null;
        }
        String workdir = String.valueOf(args.get("workdir")).trim();
        if (!StringUtils.hasText(workdir)) {
            return null;
        }
        Path normalized = Path.of(workdir).toAbsolutePath().normalize();
        Path translated = translateWorkspaceAlias(normalized, sandboxPolicy);
        return workspacePathResolver.validateWithinWorkspaceRoot(translated);
    }

    private Path translateWorkspaceAlias(Path requestedPath, SandboxPolicy sandboxPolicy) {
        if (requestedPath == null || sandboxPolicy == null || sandboxPolicy.getDefaultWorkingDirectory() == null) {
            return requestedPath;
        }
        Set<Path> aliases = sandboxPolicy.getWorkspacePathAliases();
        if (CollectionUtils.isEmpty(aliases)) {
            return requestedPath;
        }
        Path normalizedRequested = requestedPath.toAbsolutePath().normalize();
        for (Path alias : aliases) {
            if (alias == null) {
                continue;
            }
            Path normalizedAlias = alias.toAbsolutePath().normalize();
            if (!normalizedRequested.startsWith(normalizedAlias)) {
                continue;
            }
            Path relativePath = normalizedAlias.relativize(normalizedRequested);
            return sandboxPolicy.getDefaultWorkingDirectory().resolve(relativePath).normalize();
        }
        return requestedPath;
    }

    private boolean requiresWorkingDirectory(String toolCode) {
        return StringUtils.hasText(toolCode) && toolCode.startsWith("sys.");
    }

    private ToolResult executeProcess(ProcessBuilder processBuilder, ToolCall toolCall, String toolCode,
                                       LocalDateTime startAt, Map<String, Object> safeArgsForLog,
                                       String tenantId, String agentId) throws Exception {
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
                    SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, LocalDateTime.now(), safeArgsForLog, null,
                    ToolExecutionErrorCode.COMMAND_FAILED, error);
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
            String sanitizedOutput = truncateCommandOutput(output);
            Map<String, Object> result = Map.of("output", sanitizedOutput);
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
                    SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(), startAt, endAt, safeArgsForLog, null,
                    ToolExecutionErrorCode.COMMAND_FAILED, error);
            return failure(toolCall, error);
        }
    }

    private ToolResult validateReadablePathExists(String toolCode, ToolCall toolCall, Map<String, Object> args,
                                                  Path workingDirectory, LocalDateTime startAt,
                                                  Map<String, Object> safeArgsForLog, String tenantId,
                                                  String agentId) {
        if (workingDirectory == null || args == null || !Set.of("sys.read", "sys.stat").contains(toolCode)) {
            return null;
        }
        String pathValue = asString(args.get("path"));
        if (!StringUtils.hasText(pathValue)) {
            return null;
        }
        Path target = workingDirectory.resolve(pathValue).normalize();
        if (java.nio.file.Files.exists(target)) {
            return null;
        }
        String error = "系统工具路径不存在: " + pathValue;
        Map<String, Object> response = Map.of(
                "errorCode", ToolExecutionErrorCode.PATH_NOT_FOUND,
                "path", pathValue,
                "workdir", workingDirectory.toString(),
                "suggestion", "请先使用 sys.ls 查看可用目录或修正相对路径"
        );
        logService.logExecution(tenantId, agentId, null, toolCall.getCallId(),
                SourceTypeEnum.BUILTIN.getCode(), toolCode, ToolExecutionStatusEnum.FAILED.getCode(),
                startAt, LocalDateTime.now(), safeArgsForLog, response,
                ToolExecutionErrorCode.PATH_NOT_FOUND, error);
        return failure(toolCall, error);
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

    private record LinkCandidate(String url, String text, int priority) {
    }

    private ToolIoTypeEnum resolveIoType(String toolCode) {
        if (!StringUtils.hasText(toolCode)) {
            return ToolIoTypeEnum.READ_WRITE;
        }
        ToolIoTypeEnum cached = IO_TYPE_CACHE.getIfPresent(toolCode);
        if (cached != null) {
            return cached;
        }
        try {
            BuiltinTool tool = builtinToolMapper.selectOne(
                    new LambdaQueryWrapper<BuiltinTool>()
                            .eq(BuiltinTool::getCode, toolCode)
                            .last("LIMIT 1"));
            ToolIoTypeEnum ioType = (tool != null && StringUtils.hasText(tool.getIoType()))
                    ? ToolIoTypeEnum.fromCode(tool.getIoType())
                    : ToolIoTypeEnum.READ_WRITE;
            IO_TYPE_CACHE.put(toolCode, ioType);
            return ioType;
        } catch (Exception e) {
            log.warn("查询工具 ioType 失败，使用默认值: toolCode={}, error={}", toolCode, e.getMessage());
            return ToolIoTypeEnum.READ_WRITE;
        }
    }
}
