package com.schemaplexai.service.tool.loader;

import com.schemaplexai.common.enums.CustomToolTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 脚本执行器
 * <p>
 * 支持 JavaScript（Nashorn）和 Python 脚本执行。
 * Python 脚本通过 Process 执行，结果通过 stdout 返回。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptExecutor {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final String NASHORN_ENGINE_NAME = "nashorn";

    private final ScriptEngineManager scriptEngineManager;

    /**
     * 执行脚本
     *
     * @param type   脚本类型
     * @param script 脚本内容或文件路径
     * @param args   参数
     * @return 执行结果
     */
    public Object execute(CustomToolTypeEnum type, String script, Map<String, Object> args) {
        if (type == null || !type.isScript()) {
            throw new IllegalArgumentException("不支持的脚本类型: " + type);
        }

        return switch (type) {
            case JAVASCRIPT -> executeJavaScript(script, args);
            case PYTHON -> executePython(script, args);
            default -> throw new IllegalArgumentException("不支持的脚本类型: " + type);
        };
    }

    /**
     * 执行 JavaScript 脚本
     */
    public Object executeJavaScript(String script, Map<String, Object> args) {
        try {
            ScriptEngine engine = scriptEngineManager.getEngineByName(NASHORN_ENGINE_NAME);
            if (engine == null) {
                throw new RuntimeException("Nashorn 引擎不可用");
            }

            Bindings bindings = new SimpleBindings();
            if (args != null) {
                bindings.putAll(args);
            }

            Object result = engine.eval(script, bindings);
            log.debug("JavaScript 脚本执行成功");
            return result;
        } catch (ScriptException e) {
            log.error("JavaScript 脚本执行失败: {}", e.getMessage());
            throw new RuntimeException("脚本执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行 Python 脚本
     * <p>
     * 通过 Process 执行 Python 解释器，支持 pyexec/py 等常见命令。
     * </p>
     */
    public Object executePython(String script, Map<String, Object> args) {
        try {
            // 构建 Python 脚本：注入参数
            String fullScript = buildPythonScript(script, args);

            // 查找 Python 解释器
            String pythonCmd = findPythonInterpreter();
            ProcessBuilder pb = new ProcessBuilder(pythonCmd, "-c", fullScript);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // 读取输出
            String output;
            try {
                output = new String(process.getInputStream().readAllBytes());
                boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new RuntimeException("脚本执行超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("脚本执行被中断");
            }

            if (process.exitValue() != 0) {
                throw new RuntimeException("脚本执行失败，退出码: " + process.exitValue() + "\n输出: " + output);
            }

            log.debug("Python 脚本执行成功");
            return output.trim();
        } catch (IOException e) {
            log.error("Python 脚本执行失败: {}", e.getMessage());
            throw new RuntimeException("脚本执行失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行脚本文件
     */
    public Object executeFile(CustomToolTypeEnum type, Path scriptFile, Map<String, Object> args) {
        try {
            String content = Files.readString(scriptFile);
            return execute(type, content, args);
        } catch (IOException e) {
            throw new RuntimeException("读取脚本文件失败: " + e.getMessage(), e);
        }
    }

    // ==================== 私有方法 ====================

    private String buildPythonScript(String script, Map<String, Object> args) {
        StringBuilder sb = new StringBuilder();

        // 注入参数到全局变量
        if (args != null && !args.isEmpty()) {
            sb.append("import json\n");
            sb.append("args = json.loads('").append(escapeJson(args)).append("')\n");
        }

        sb.append(script);
        return sb.toString();
    }

    private String findPythonInterpreter() {
        // 尝试常见 Python 命令
        String[] commands = {"python3", "python", "pyexec"};
        for (String cmd : commands) {
            try {
                Process check = new ProcessBuilder(cmd, "--version").start();
                int exitCode = check.waitFor();
                if (exitCode == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {
            }
        }
        throw new RuntimeException("未找到可用的 Python 解释器（python3/python/pyexec）");
    }

    private String escapeJson(Object obj) {
        return obj.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
