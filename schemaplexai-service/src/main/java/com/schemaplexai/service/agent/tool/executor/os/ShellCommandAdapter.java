package com.schemaplexai.service.agent.tool.executor.os;

import java.util.Map;

public interface ShellCommandAdapter {
    String adaptCommand(String toolCode, Map<String, Object> args);
    boolean supports(String osType);
}
