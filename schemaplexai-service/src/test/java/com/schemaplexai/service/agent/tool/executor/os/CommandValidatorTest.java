package com.schemaplexai.service.agent.tool.executor.os;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommandValidatorTest {

    private final CommandValidator commandValidator = new CommandValidator();

    @Test
    void shouldAllowMacWriteCommandForMultilineContent() {
        Map<String, Object> args = writeArgs();
        MacCommandAdapter adapter = new MacCommandAdapter();

        assertWriteCommandAllowed(adapter.adaptCommand("sys.write", args), args);
    }

    @Test
    void shouldAllowLinuxWriteCommandForMultilineContent() {
        Map<String, Object> args = writeArgs();
        LinuxCommandAdapter adapter = new LinuxCommandAdapter();

        assertWriteCommandAllowed(adapter.adaptCommand("sys.write", args), args);
    }

    @Test
    void shouldAllowWindowsWriteCommandForMultilineContent() {
        Map<String, Object> args = writeArgs();
        WindowsCommandAdapter adapter = new WindowsCommandAdapter();

        assertWriteCommandAllowed(adapter.adaptCommand("sys.write", args), args);
    }

    @Test
    void shouldAllowReadPathWithChineseCharacters() {
        Map<String, Object> args = Map.of("path", "docs/32-Spec工作流化与人工审核通知改造设计方案.md");

        assertThat(commandValidator.validateToolArguments("sys.read", args)).isTrue();
    }

    @Test
    void shouldAllowGrepPatternWithAlternation() {
        Map<String, Object> args = Map.of(
                "path", "src",
                "pattern", "preview|approval|Strategy"
        );

        assertThat(commandValidator.validateToolArguments("sys.grep", args)).isTrue();
    }

    @Test
    void shouldAllowGrepPatternWithSpringAnnotations() {
        Map<String, Object> args = Map.of(
                "path", "titanium-policy-web/src/main/java",
                "pattern", "@RestController|@RequestMapping|@PostMapping|@GetMapping|@PutMapping|@DeleteMapping"
        );

        assertThat(commandValidator.validateToolArguments("sys.grep", args)).isTrue();
    }

    @Test
    void shouldUseExtendedRegexForMacAndLinuxGrep() {
        Map<String, Object> args = Map.of(
                "path", "src",
                "pattern", "preview|approval|Strategy"
        );

        assertThat(new MacCommandAdapter().adaptCommand("sys.grep", args))
                .startsWith("grep -rE ");
        assertThat(new LinuxCommandAdapter().adaptCommand("sys.grep", args))
                .startsWith("grep -rE ");
    }

    @Test
    void shouldAllowCurlCommandForBashResearch() {
        Map<String, Object> args = Map.of("command", "curl -sI https://example.com");

        assertThat(commandValidator.validateToolArguments("sys.bash", args)).isTrue();
        assertThat(commandValidator.isCommandAllowed("sys.bash", "curl -sI https://example.com")).isTrue();
    }

    @Test
    void shouldRejectCurlCommandWithPipeSyntax() {
        Map<String, Object> args = Map.of("command", "curl -s https://example.com | head");

        assertThat(commandValidator.validateToolArguments("sys.bash", args)).isFalse();
        assertThat(commandValidator.isCommandAllowed("sys.bash", "curl -s https://example.com | head")).isFalse();
    }

    @Test
    void shouldRejectBashCommandWithPathEscape() {
        Map<String, Object> args = Map.of("command", "cat ../secrets/application.yml");

        assertThat(commandValidator.validateToolArguments("sys.bash", args)).isFalse();
        assertThat(commandValidator.isCommandAllowed("sys.bash", "cat ../secrets/application.yml")).isFalse();
    }

    private void assertWriteCommandAllowed(String command, Map<String, Object> args) {
        assertThat(commandValidator.validateToolArguments("sys.write", args)).isTrue();
        assertThat(command).doesNotContain("\n", "\r");
        assertThat(commandValidator.isCommandAllowed("sys.write", command)).isTrue();
    }

    private Map<String, Object> writeArgs() {
        return Map.of(
                "path", "docs/workflow-design.md",
                "content", "# 工作流设计\n\n- 第一行\n- 第二行\n\n结论：允许多行 Markdown 正常落盘。"
        );
    }
}
