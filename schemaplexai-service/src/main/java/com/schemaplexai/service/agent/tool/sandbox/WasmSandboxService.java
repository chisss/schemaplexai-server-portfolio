package com.schemaplexai.service.agent.tool.sandbox;

import io.roastedroot.quickjs4j.core.Runner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Wasm 代码沙箱执行服务
 * <p>基于 Chicory + QuickJs4j，在 JVM 内以 Wasm 双重隔离方式安全执行代码。
 * <ul>
 *   <li>内存隔离：Wasm 线性内存 + JVM 沙箱</li>
 *   <li>无文件/网络/系统访问</li>
 *   <li>超时控制 + 并发限流</li>
 * </ul>
 */
@Slf4j
@Component
public class WasmSandboxService {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("javascript", "js");

    private final CodeExecProperties properties;
    private final Semaphore concurrencyLimiter;

    public WasmSandboxService(CodeExecProperties properties) {
        this.properties = properties;
        this.concurrencyLimiter = new Semaphore(properties.getMaxConcurrentExecutions());
    }

    /**
     * 在 Wasm 沙箱中执行代码
     */
    public CodeExecResult execute(CodeExecRequest request) {
        // 前置校验
        if (!properties.isEnabled()) {
            return errorResult(request, 4, "代码沙箱功能未启用");
        }
        if (!StringUtils.hasText(request.getLanguage())
                || !SUPPORTED_LANGUAGES.contains(request.getLanguage().toLowerCase())) {
            return errorResult(request, 4,
                    "不支持的语言: " + request.getLanguage() + "，当前仅支持 javascript");
        }
        if (!StringUtils.hasText(request.getCode())) {
            return errorResult(request, 3, "代码不能为空");
        }
        if (request.getCode().length() > properties.getMaxCodeLength()) {
            return errorResult(request, 4,
                    "代码长度超限: " + request.getCode().length() + " > " + properties.getMaxCodeLength());
        }

        // 计算超时
        int timeout = request.getTimeoutMs() > 0
                ? Math.min(request.getTimeoutMs(), properties.getMaxTimeoutMs())
                : properties.getDefaultTimeoutMs();

        // 并发限流
        if (!concurrencyLimiter.tryAcquire()) {
            return errorResult(request, 4,
                    "代码沙箱并发数已满（上限 " + properties.getMaxConcurrentExecutions() + "），请稍后重试");
        }

        long startMs = System.currentTimeMillis();
        try {
            return executeJavaScript(request, timeout, startMs);
        } finally {
            concurrencyLimiter.release();
        }
    }

    private CodeExecResult executeJavaScript(CodeExecRequest request, int timeout, long startMs) {
        try (Runner runner = Runner.builder()
                .withTimeoutMs(timeout)
                .build()) {

            runner.compileAndExec(request.getCode());

            String stdout = truncateOutput(runner.stdout());
            String stderr = truncateOutput(runner.stderr());
            long elapsed = System.currentTimeMillis() - startMs;

            log.debug("代码沙箱执行完成: language={}, elapsed={}ms, stdoutLen={}, stderrLen={}",
                    request.getLanguage(), elapsed,
                    stdout != null ? stdout.length() : 0,
                    stderr != null ? stderr.length() : 0);

            // 有 stderr 但无异常，视为执行成功但有警告
            int exitCode = 0;
            return CodeExecResult.builder()
                    .stdout(stdout)
                    .stderr(stderr)
                    .exitCode(exitCode)
                    .executionMs(elapsed)
                    .language(request.getLanguage())
                    .build();

        } catch (RuntimeException e) {
            long elapsed = System.currentTimeMillis() - startMs;
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            // 区分超时和其他错误
            if (isTimeoutError(e)) {
                log.warn("代码沙箱执行超时: timeout={}ms, elapsed={}ms", timeout, elapsed);
                return CodeExecResult.builder()
                        .stdout("")
                        .stderr("执行超时（限制 " + timeout + "ms）")
                        .exitCode(2)
                        .executionMs(elapsed)
                        .language(request.getLanguage())
                        .build();
            }

            // 区分语法错误和运行时错误
            int exitCode = isSyntaxError(message) ? 3 : 1;
            log.warn("代码沙箱执行异常: exitCode={}, error={}", exitCode, message);
            return CodeExecResult.builder()
                    .stdout("")
                    .stderr(truncateOutput(message))
                    .exitCode(exitCode)
                    .executionMs(elapsed)
                    .language(request.getLanguage())
                    .build();
        }
    }

    private boolean isTimeoutError(RuntimeException e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        return msg.contains("Timeout while") || e.getCause() instanceof java.util.concurrent.TimeoutException;
    }

    private boolean isSyntaxError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("syntaxerror")
                || lower.contains("syntax error")
                || lower.contains("unexpected token")
                || lower.contains("parse error");
    }

    private String truncateOutput(String output) {
        if (output == null) {
            return "";
        }
        int max = properties.getMaxOutputLength();
        if (output.length() <= max) {
            return output;
        }
        return output.substring(0, max) + "\n...[输出过长，已截断至 " + max + " 字符]";
    }

    private CodeExecResult errorResult(CodeExecRequest request, int exitCode, String message) {
        return CodeExecResult.builder()
                .stdout("")
                .stderr(message)
                .exitCode(exitCode)
                .executionMs(0)
                .language(request != null ? request.getLanguage() : "unknown")
                .build();
    }
}
