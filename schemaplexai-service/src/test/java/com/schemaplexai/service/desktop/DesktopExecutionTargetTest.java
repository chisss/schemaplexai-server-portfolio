package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.model.entity.AgentExecution;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DesktopExecutionTargetTest {

    @Test
    void shouldKeepCloudExecutionAndRequireCompleteExplicitDesktopTarget() {
        assertThat(DesktopExecutionTarget.from(null)).isNull();
        assertThat(DesktopExecutionTarget.from(Map.of("business", "context"))).isNull();
        assertThatThrownBy(() -> DesktopExecutionTarget.from(Map.of("desktopDeviceId", "device")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DesktopExecutionTarget.from(Map.of("desktopDeviceId", "", "desktopWorkspaceId", "w")))
                .isInstanceOf(BusinessException.class);
        assertThat(DesktopExecutionTarget.from(Map.of("desktopDeviceId", "device", "desktopWorkspaceId", "workspace")))
                .isEqualTo(new DesktopExecutionTarget("device", "workspace"));
    }

    @Test
    void shouldPreventOtherUserFromResumingOrReadingDesktopExecution() {
        AgentExecution execution = new AgentExecution();
        execution.setCreatedBy("owner");
        execution.setInputContext(Map.of("desktopDeviceId", "device", "desktopWorkspaceId", "workspace"));
        assertThatThrownBy(() -> DesktopExecutionTarget.requireOwner(execution, "other-user"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> DesktopExecutionTarget.requireOwner(execution, null))
                .isInstanceOf(BusinessException.class);
        assertThatCode(() -> DesktopExecutionTarget.requireOwner(execution, "owner")).doesNotThrowAnyException();
        execution.setInputContext(null);
        assertThatCode(() -> DesktopExecutionTarget.requireOwner(execution, "other-user")).doesNotThrowAnyException();
    }
}
