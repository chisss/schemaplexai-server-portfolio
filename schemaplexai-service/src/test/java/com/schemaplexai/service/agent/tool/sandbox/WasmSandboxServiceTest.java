package com.schemaplexai.service.agent.tool.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WasmSandboxService 单元测试
 * <p>验证 Chicory + QuickJs4j 代码沙箱的核心功能:
 * 正常执行、错误处理、超时控制、隔离安全性、资源限制
 */
class WasmSandboxServiceTest {

    private WasmSandboxService service;
    private CodeExecProperties properties;

    @BeforeEach
    void setUp() {
        properties = new CodeExecProperties();
        properties.setEnabled(true);
        properties.setDefaultTimeoutMs(5000);
        properties.setMaxTimeoutMs(30000);
        properties.setMaxCodeLength(50000);
        properties.setMaxOutputLength(24000);
        properties.setMaxConcurrentExecutions(4);
        service = new WasmSandboxService(properties);
    }

    @Test
    @DisplayName("正常执行: console.log 输出")
    void shouldExecuteConsoleLog() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("console.log('hello world');")
                .timeoutMs(5000)
                .build());

        assertEquals(0, result.getExitCode(), "exitCode 应为 0");
        assertTrue(result.getStdout().contains("hello world"), "stdout 应包含 hello world");
        assertEquals("javascript", result.getLanguage());
        assertTrue(result.getExecutionMs() >= 0);
    }

    @Test
    @DisplayName("正常执行: 算术计算")
    void shouldExecuteArithmetic() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("console.log(2 + 3 * 4);")
                .timeoutMs(5000)
                .build());

        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("14"));
    }

    @Test
    @DisplayName("正常执行: JSON 处理")
    void shouldExecuteJsonProcessing() {
        String code = """
                var data = [1, 2, 3, 4, 5];
                var sum = data.reduce(function(a, b) { return a + b; }, 0);
                var avg = sum / data.length;
                console.log(JSON.stringify({sum: sum, avg: avg}));
                """;
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code(code)
                .timeoutMs(5000)
                .build());

        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("\"sum\":15"));
        assertTrue(result.getStdout().contains("\"avg\":3"));
    }

    @Test
    @DisplayName("语法错误处理")
    void shouldHandleSyntaxError() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("function( { broken")
                .timeoutMs(5000)
                .build());

        // QuickJS 语法错误可能返回 exitCode 1 或 3，关键是非0且有错误信息
        assertTrue(result.getExitCode() > 0, "语法错误 exitCode 应大于 0");
        assertTrue(result.getStderr().length() > 0, "stderr 应包含错误信息");
    }

    @Test
    @DisplayName("运行时错误处理")
    void shouldHandleRuntimeError() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("undefinedVariable.property;")
                .timeoutMs(5000)
                .build());

        assertTrue(result.getExitCode() == 1 || result.getExitCode() == 3,
                "运行时错误 exitCode 应为 1 或 3");
        assertTrue(result.getStderr().length() > 0);
    }

    @Test
    @DisplayName("超时控制: 死循环应被终止")
    void shouldTerminateInfiniteLoop() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("while(true) {}")
                .timeoutMs(1000)
                .build());

        assertEquals(2, result.getExitCode(), "超时 exitCode 应为 2");
        assertTrue(result.getStderr().contains("超时"), "stderr 应包含超时提示");
        assertTrue(result.getExecutionMs() >= 900, "执行时间应接近超时值");
    }

    @Test
    @DisplayName("不支持的语言拒绝")
    void shouldRejectUnsupportedLanguage() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("python")
                .code("print('hello')")
                .timeoutMs(5000)
                .build());

        assertEquals(4, result.getExitCode(), "不支持的语言 exitCode 应为 4");
        assertTrue(result.getStderr().contains("不支持的语言"));
    }

    @Test
    @DisplayName("空代码拒绝")
    void shouldRejectEmptyCode() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("")
                .timeoutMs(5000)
                .build());

        assertEquals(3, result.getExitCode(), "空代码 exitCode 应为 3");
    }

    @Test
    @DisplayName("代码长度超限拒绝")
    void shouldRejectOversizedCode() {
        String longCode = "x".repeat(50001);
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code(longCode)
                .timeoutMs(5000)
                .build());

        assertEquals(4, result.getExitCode(), "超长代码 exitCode 应为 4");
        assertTrue(result.getStderr().contains("超限"));
    }

    @Test
    @DisplayName("服务禁用时拒绝")
    void shouldRejectWhenDisabled() {
        properties.setEnabled(false);
        WasmSandboxService disabledService = new WasmSandboxService(properties);

        CodeExecResult result = disabledService.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("console.log(1);")
                .timeoutMs(5000)
                .build());

        assertEquals(4, result.getExitCode());
        assertTrue(result.getStderr().contains("未启用"));
    }

    @Test
    @DisplayName("语言别名 js 应等同于 javascript")
    void shouldAcceptJsAlias() {
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("js")
                .code("console.log('js alias');")
                .timeoutMs(5000)
                .build());

        assertEquals(0, result.getExitCode());
        assertTrue(result.getStdout().contains("js alias"));
    }

    @Test
    @DisplayName("超时上限裁剪: 超过 maxTimeoutMs 自动裁剪")
    void shouldClampTimeout() {
        // 传入 60000ms，但 maxTimeoutMs=30000，应被裁剪
        // 这里只验证不会抛异常，实际超时值由内部控制
        CodeExecResult result = service.execute(CodeExecRequest.builder()
                .language("javascript")
                .code("console.log('ok');")
                .timeoutMs(60000)
                .build());

        assertEquals(0, result.getExitCode());
    }
}
