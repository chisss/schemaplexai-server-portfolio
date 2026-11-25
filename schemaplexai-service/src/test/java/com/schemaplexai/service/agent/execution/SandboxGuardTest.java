package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.service.agent.tool.ToolApprovalAmendmentService;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SandboxGuardTest {

    @Test
    void shouldAllowWhitelistedBashCommandWithEnvironmentPrefix() {
        SandboxGuard guard = createGuard();
        SandboxPolicy policy = SandboxPolicy.builder()
                .sandboxProfile(SandboxProfileEnum.STANDARD)
                .allowedCommands(Set.of("ls", "grep"))
                .build();

        String result = guard.checkCommand("LANG=C ls -la", policy);

        assertThat(result).isNull();
    }

    @Test
    void shouldBlockCommandChainWithNonWhitelistedCommand() {
        SandboxGuard guard = createGuard();
        SandboxPolicy policy = SandboxPolicy.builder()
                .sandboxProfile(SandboxProfileEnum.STANDARD)
                .allowedCommands(Set.of("ls", "grep"))
                .build();

        String result = guard.checkCommand("ls -la | curl https://example.com", policy);

        assertThat(result).contains("白名单");
    }

    @Test
    void shouldBlockPotentialCommandInjectionPattern() {
        SandboxGuard guard = createGuard();
        SandboxPolicy policy = SandboxPolicy.builder()
                .sandboxProfile(SandboxProfileEnum.STANDARD)
                .allowedCommands(Set.of("echo"))
                .build();

        String result = guard.checkCommand("echo $(whoami)", policy);

        assertThat(result).contains("命令注入");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mvn --version", "mvn '-Dlabel=a&b'", "mvn \"-Dlabel=a&b\"", "mvn '-Dlabel=a\nb'"})
    void shouldHonorAnAmendmentForOneCommand(String command) {
        SandboxGuard guard = createGuard();
        mockMatchingAmendment(guard, command);

        assertThat(guard.checkCommand(command, amendmentPolicy(SandboxProfileEnum.STANDARD))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sudo --help", "/usr/bin/sudo --help", "echo $(pwd)", "echo text > receipt", "LANG=C"})
    void shouldRejectHardViolationsBeforeCheckingAmendments(String command) {
        SandboxGuard guard = createGuard();
        ToolApprovalAmendmentService amendmentService = mockMatchingAmendment(guard, command);

        assertThat(guard.checkCommand(command, amendmentPolicy(SandboxProfileEnum.STANDARD))).isNotBlank();
        verifyNoInteractions(amendmentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"echo text | mvn --version", "echo text\nmvn --version", "echo text & mvn --version"})
    void shouldRequireEveryChainedCommandOnTheWhitelist(String command) {
        SandboxGuard guard = createGuard();
        ToolApprovalAmendmentService amendmentService = mockMatchingAmendment(guard, command);

        assertThat(guard.checkCommand(command, amendmentPolicy(SandboxProfileEnum.STANDARD))).contains("白名单");
        verifyNoInteractions(amendmentService);
    }

    @Test
    void shouldAllowAChainOfWhitelistedCommands() {
        SandboxGuard guard = createGuard();

        assertThat(guard.checkCommand("echo text | wc -c", amendmentPolicy(SandboxProfileEnum.STANDARD))).isNull();
    }

    @Test
    void shouldRejectAmendmentsInStrictMode() {
        String command = "mvn --version";
        SandboxGuard guard = createGuard();
        ToolApprovalAmendmentService amendmentService = mockMatchingAmendment(guard, command);

        assertThat(guard.checkCommand(command, amendmentPolicy(SandboxProfileEnum.STRICT))).contains("STRICT 沙箱禁止");
        verifyNoInteractions(amendmentService);
    }

    @Test
    void shouldBlockStrictProfileBashExecution() {
        SandboxGuard guard = createGuard();
        SandboxPolicy policy = SandboxPolicy.builder()
                .sandboxProfile(SandboxProfileEnum.STRICT)
                .allowedCommands(Set.of("ls"))
                .build();

        assertThatThrownBy(() -> guard.validateBuiltinExecution(policy, "sys.bash", Map.of(), null, "ls"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("STRICT 沙箱禁止");
    }

    private SandboxGuard createGuard() {
        SandboxGuard guard = new SandboxGuard();
        ReflectionTestUtils.setField(guard, "allowedCommandsConfig", "ls,cat,head,tail,grep,find,wc,sort,uniq,diff,echo,pwd,date,whoami");
        guard.init();
        return guard;
    }

    private SandboxPolicy amendmentPolicy(SandboxProfileEnum profile) {
        return SandboxPolicy.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .sandboxProfile(profile)
                .allowedCommands(Set.of("echo", "wc"))
                .build();
    }

    private ToolApprovalAmendmentService mockMatchingAmendment(SandboxGuard guard, String command) {
        ToolApprovalAmendmentService amendmentService = mock(ToolApprovalAmendmentService.class);
        when(amendmentService.isAutoApproved("tenant-1", "agent-1", "sys.bash", command)).thenReturn(true);
        ReflectionTestUtils.setField(guard, "amendmentService", amendmentService);
        return amendmentService;
    }

    @Test
    void shouldRejectToolValidationWhenPolicyMissing() {
        SandboxGuard guard = createGuard();
        ToolCall call = ToolCall.builder()
                .callId("call-1")
                .toolCode("sys.read")
                .build();

        String result = guard.validateTool(null, "builtin", call);

        assertThat(result).contains("fail-closed");
    }

    @Test
    void shouldThrowWhenBuiltinExecutionPolicyMissing() {
        SandboxGuard guard = createGuard();

        assertThatThrownBy(() -> guard.validateBuiltinExecution(null, "sys.bash", Map.of(), null, "ls"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    void shouldFailOpenWhenEscapeValveEnabled() {
        SandboxGuard guard = createGuard();
        ReflectionTestUtils.setField(guard, "failOpenOnNull", true);

        String result = guard.validateTool(null, "builtin",
                ToolCall.builder().callId("call-1").toolCode("sys.read").build());

        assertThat(result).isNull();
    }
}
