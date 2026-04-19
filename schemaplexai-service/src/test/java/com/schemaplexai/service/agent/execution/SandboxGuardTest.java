package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void shouldBlockStrictProfileBashExecution() {
        SandboxGuard guard = createGuard();
        SandboxPolicy policy = SandboxPolicy.builder()
                .sandboxProfile(SandboxProfileEnum.STRICT)
                .allowedCommands(Set.of("ls"))
                .build();

        assertThatThrownBy(() -> guard.validateBuiltinExecution(policy, "sys.bash", java.util.Map.of(), null, "ls"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("STRICT 沙箱禁止");
    }

    private SandboxGuard createGuard() {
        SandboxGuard guard = new SandboxGuard();
        ReflectionTestUtils.setField(guard, "allowedCommandsConfig", "ls,cat,head,tail,grep,find,wc,sort,uniq,diff,echo,pwd,date,whoami");
        guard.init();
        return guard;
    }
}
