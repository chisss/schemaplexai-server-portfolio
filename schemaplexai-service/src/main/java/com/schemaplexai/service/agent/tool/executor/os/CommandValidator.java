package com.schemaplexai.service.agent.tool.executor.os;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class CommandValidator {

    private static final Pattern SAFE_PATH = Pattern.compile("^[\\p{L}\\p{N}_./\\\\\\-\\s:()\\[\\]#]+$");
    /** grep 模式需要支持 Spring 注解等常见采证场景，因此放开 @ 字符。 */
    private static final Pattern SAFE_PATTERN = Pattern.compile("^[\\p{L}\\p{N}_./\\\\\\-\\s:*?()\\[\\]#|+$^@]+$");
    private static final Set<String> SAFE_BASH_COMMANDS = Set.of(
            "ls", "pwd", "date", "cat", "grep", "find", "mkdir", "rm", "cp", "mv", "stat",
            "echo", "head", "tail", "wc", "whoami", "type", "dir", "findstr", "attrib",
            "curl"
    );

    public boolean validateToolArguments(String toolCode, Map<String, Object> args) {
        Map<String, Object> safeArgs = args == null ? Map.of() : args;
        return switch (toolCode) {
            case "sys.read", "sys.stat", "sys.rm", "sys.mkdir" -> validatePathArg(safeArgs, "path");
            case "sys.ls" -> !safeArgs.containsKey("path")
                    || !StringUtils.hasText(asText(safeArgs.get("path")))
                    || validatePath(asText(safeArgs.get("path")));
            case "sys.write", "sys.edit" -> validatePathArg(safeArgs, "path") && validateContentArg(safeArgs, "content");
            case "sys.glob", "sys.grep" -> validatePatternArg(safeArgs, "pattern")
                    && (!safeArgs.containsKey("path") || validatePath(asText(safeArgs.get("path"))));
            case "sys.cp", "sys.mv" -> validatePathArg(safeArgs, "source") && validatePathArg(safeArgs, "target");
            case "sys.bash" -> validateBashArg(safeArgs, "command");
            default -> false;
        };
    }

    public boolean isCommandAllowed(String toolCode, String command) {
        if (!StringUtils.hasText(command)) {
            return false;
        }
        if (command.contains("\n") || command.contains("\r")) {
            return false;
        }
        if (command.contains("`") || command.contains("$(")) {
            return false;
        }
        if ("sys.bash".equals(toolCode) && (containsUnsafeShellSyntax(command) || containsUnsafePathReference(command))) {
            return false;
        }

        String firstWord = command.trim().split("\\s+")[0].toLowerCase();
        return switch (toolCode) {
            case "sys.read" -> Set.of("cat", "type").contains(firstWord);
            case "sys.write", "sys.edit" -> Set.of("printf", "powershell").contains(firstWord);
            case "sys.bash" -> SAFE_BASH_COMMANDS.contains(firstWord);
            case "sys.glob" -> Set.of("find", "dir").contains(firstWord);
            case "sys.grep" -> Set.of("grep", "findstr").contains(firstWord);
            case "sys.ls" -> Set.of("ls", "dir").contains(firstWord);
            case "sys.mkdir" -> "mkdir".equals(firstWord);
            case "sys.rm" -> Set.of("rm", "del", "powershell").contains(firstWord);
            case "sys.cp" -> Set.of("cp", "copy").contains(firstWord);
            case "sys.mv" -> Set.of("mv", "move").contains(firstWord);
            case "sys.stat" -> Set.of("stat", "attrib").contains(firstWord);
            default -> false;
        };
    }

    private boolean validatePathArg(Map<String, Object> args, String key) {
        return validatePath(asText(args.get(key)));
    }

    private boolean validatePatternArg(Map<String, Object> args, String key) {
        String pattern = asText(args.get(key));
        if (!StringUtils.hasText(pattern) || pattern.length() > 256) {
            return false;
        }
        if (pattern.contains("..")) {
            return false;
        }
        return SAFE_PATTERN.matcher(pattern).matches();
    }

    private boolean validateContentArg(Map<String, Object> args, String key) {
        if (!args.containsKey(key) || args.get(key) == null) {
            return false;
        }
        String content = String.valueOf(args.get(key));
        return content.length() <= 20_000;
    }

    private boolean validateBashArg(Map<String, Object> args, String key) {
        String command = asText(args.get(key));
        if (!StringUtils.hasText(command) || command.length() > 2000) {
            return false;
        }
        if (command.contains("\n") || command.contains("\r")) {
            return false;
        }
        if (containsUnsafeShellSyntax(command) || containsUnsafePathReference(command)) {
            return false;
        }
        String firstWord = command.split("\\s+")[0].toLowerCase();
        return SAFE_BASH_COMMANDS.contains(firstWord);
    }

    private boolean validatePath(String path) {
        if (!StringUtils.hasText(path) || path.length() > 512) {
            return false;
        }
        if (path.contains("..")) {
            return false;
        }
        if (path.contains("\n") || path.contains("\r") || path.contains("\u0000")) {
            return false;
        }
        // 限制绝对路径，避免越权访问宿主机
        if (path.startsWith("/") || path.startsWith("\\") || path.matches("^[a-zA-Z]:[\\\\/].*")) {
            return false;
        }
        return SAFE_PATH.matcher(path).matches();
    }

    private String asText(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean containsUnsafeShellSyntax(String command) {
        return command.contains("&&")
                || command.contains("||")
                || command.contains(";")
                || command.contains("|")
                || command.contains("&")
                || command.contains("`")
                || command.contains("$(")
                || command.contains(">")
                || command.contains("<");
    }

    private boolean containsUnsafePathReference(String command) {
        String[] tokens = command.trim().split("\\s+");
        for (String token : tokens) {
            String normalized = stripQuotes(token);
            if (!StringUtils.hasText(normalized) || normalized.startsWith("-")) {
                continue;
            }
            if (normalized.startsWith("/") || normalized.startsWith("\\") || normalized.matches("^[a-zA-Z]:[\\\\/].*")) {
                return true;
            }
            if (normalized.startsWith("..") || normalized.contains("../") || normalized.contains("..\\")) {
                return true;
            }
        }
        return false;
    }

    private String stripQuotes(String token) {
        if (token.length() >= 2) {
            if ((token.startsWith("\"") && token.endsWith("\"")) || (token.startsWith("'") && token.endsWith("'"))) {
                return token.substring(1, token.length() - 1);
            }
        }
        return token;
    }
}
