package com.schemaplexai.service.agent.tool.executor.os;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class LinuxCommandAdapter implements ShellCommandAdapter {

    @Override
    public String adaptCommand(String toolCode, Map<String, Object> args) {
        return switch (toolCode) {
            case "sys.read" -> "cat " + args.get("path");
            case "sys.write" -> "echo '" + args.get("content") + "' > " + args.get("path");
            case "sys.bash" -> String.valueOf(args.get("command"));
            case "sys.glob" -> "find . -name '" + args.get("pattern") + "'";
            case "sys.grep" -> "grep -r '" + args.get("pattern") + "' .";
            default -> throw new UnsupportedOperationException("不支持的工具: " + toolCode);
        };
    }

    @Override
    public boolean supports(String osType) {
        return "linux".equalsIgnoreCase(osType);
    }
}
