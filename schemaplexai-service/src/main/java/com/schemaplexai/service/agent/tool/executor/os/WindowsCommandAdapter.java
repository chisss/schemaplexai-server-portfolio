package com.schemaplexai.service.agent.tool.executor.os;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class WindowsCommandAdapter implements ShellCommandAdapter {

    @Override
    public String adaptCommand(String toolCode, Map<String, Object> args) {
        return switch (toolCode) {
            case "sys.read" -> "type " + quoteForCmd(argAsString(args, "path"));
            case "sys.write", "sys.edit" -> "powershell -NoProfile -NonInteractive -Command \"Set-Content -LiteralPath "
                    + quoteForPowerShell(argAsString(args, "path"))
                    + " -Value "
                    + quoteForPowerShell(argAsString(args, "content"))
                    + "\"";
            case "sys.bash" -> argAsString(args, "command");
            case "sys.glob" -> "dir /s /b " + quoteForCmd(resolveGlobPath(args));
            case "sys.grep" -> "findstr /s /i " + quoteForCmd(argAsString(args, "pattern")) + " " + quoteForCmd(resolveGrepPath(args));
            case "sys.ls" -> hasText(argAsString(args, "path")) ? "dir " + quoteForCmd(argAsString(args, "path")) : "dir";
            case "sys.mkdir" -> "mkdir " + quoteForCmd(argAsString(args, "path"));
            case "sys.rm" -> "powershell -NoProfile -NonInteractive -Command \"Remove-Item -LiteralPath "
                    + quoteForPowerShell(argAsString(args, "path"))
                    + " -Recurse -Force\"";
            case "sys.cp" -> "copy /y " + quoteForCmd(argAsString(args, "source")) + " " + quoteForCmd(argAsString(args, "target"));
            case "sys.mv" -> "move /y " + quoteForCmd(argAsString(args, "source")) + " " + quoteForCmd(argAsString(args, "target"));
            case "sys.stat" -> "attrib " + quoteForCmd(argAsString(args, "path"));
            default -> throw new UnsupportedOperationException("不支持的工具: " + toolCode);
        };
    }

    @Override
    public boolean supports(String osType) {
        return osType != null && osType.contains("win");
    }

    @Override
    public List<String> wrapShellCommand(String command) {
        return List.of("cmd", "/c", command);
    }

    private String argAsString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String quoteForCmd(String value) {
        return "\"" + escapeForCmd(value) + "\"";
    }

    private String escapeForCmd(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("^", "^^")
                .replace("&", "^&")
                .replace("|", "^|")
                .replace("<", "^<")
                .replace(">", "^>")
                .replace("%", "%%")
                .replace("\"", "\"\"");
    }

    private String quoteForPowerShell(String value) {
        return "'" + escapePowerShellSingleQuote(value) + "'";
    }

    private String escapePowerShellSingleQuote(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private String resolveGlobPath(Map<String, Object> args) {
        String path = defaultIfBlank(argAsString(args, "path"), ".");
        String pattern = defaultIfBlank(argAsString(args, "pattern"), "*");
        if (path.endsWith("\\") || path.endsWith("/")) {
            return path + pattern;
        }
        return path + "\\" + pattern;
    }

    private String resolveGrepPath(Map<String, Object> args) {
        String path = defaultIfBlank(argAsString(args, "path"), ".");
        if (path.endsWith("\\") || path.endsWith("/")) {
            return path + "*";
        }
        return path + "\\*";
    }
}