package com.schemaplexai.service.agent.tool.executor.os;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class WindowsCommandAdapter implements ShellCommandAdapter {

    @Override
    public String adaptCommand(String toolCode, Map<String, Object> args) {
        return switch (toolCode) {
            case "sys.read" -> "type " + args.get("path");
            case "sys.write" -> "echo " + args.get("content") + " > " + args.get("path");
            case "sys.bash" -> String.valueOf(args.get("command"));
            case "sys.glob" -> "dir /s /b " + args.get("pattern");
            case "sys.grep" -> "findstr /s /i \"" + args.get("pattern") + "\" *";
            default -> throw new UnsupportedOperationException("不支持的工具: " + toolCode);
        };
    }

    @Override
    public boolean supports(String osType) {
        return "windows".equalsIgnoreCase(osType) || "win".equalsIgnoreCase(osType);
    }
}
