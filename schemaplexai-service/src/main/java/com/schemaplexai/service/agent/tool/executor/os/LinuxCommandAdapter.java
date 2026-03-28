package com.schemaplexai.service.agent.tool.executor.os;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class LinuxCommandAdapter implements ShellCommandAdapter {

    @Override
    public String adaptCommand(String toolCode, Map<String, Object> args) {
        return switch (toolCode) {
            case "sys.read" -> "cat " + quote(argAsString(args, "path"));
            case "sys.write", "sys.edit" -> "printf %s " + quote(argAsString(args, "content")) + " > " + quote(argAsString(args, "path"));
            case "sys.bash" -> argAsString(args, "command");
            case "sys.glob" -> "find " + quote(defaultIfBlank(argAsString(args, "path"), ".")) + " -name " + quote(argAsString(args, "pattern"));
            case "sys.grep" -> "grep -r " + quote(argAsString(args, "pattern")) + " " + quote(defaultIfBlank(argAsString(args, "path"), "."));
            case "sys.ls" -> "ls " + quote(defaultIfBlank(argAsString(args, "path"), "."));
            case "sys.mkdir" -> "mkdir -p " + quote(argAsString(args, "path"));
            case "sys.rm" -> "rm -rf " + quote(argAsString(args, "path"));
            case "sys.cp" -> "cp -f " + quote(argAsString(args, "source")) + " " + quote(argAsString(args, "target"));
            case "sys.mv" -> "mv -f " + quote(argAsString(args, "source")) + " " + quote(argAsString(args, "target"));
            case "sys.stat" -> "stat " + quote(argAsString(args, "path"));
            default -> throw new UnsupportedOperationException("不支持的工具: " + toolCode);
        };
    }

    @Override
    public boolean supports(String osType) {
        return osType != null && osType.contains("linux");
    }

    @Override
    public List<String> wrapShellCommand(String command) {
        return List.of("bash", "-lc", command);
    }

    private String argAsString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String quote(String value) {
        return "'" + escapeSingleQuote(value) + "'";
    }

    private String escapeSingleQuote(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "'\"'\"'");
    }
}