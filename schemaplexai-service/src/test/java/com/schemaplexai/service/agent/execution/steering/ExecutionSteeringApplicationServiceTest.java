package com.schemaplexai.service.agent.execution.steering;

import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ExecutionSteering;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行引导归属校验：租户一致 + 执行所有者（或超管），
 * 同租户其他用户不得向他人执行注入引导。
 */
class ExecutionSteeringApplicationServiceTest {

    private final ExecutionSteeringService steeringService = mock(ExecutionSteeringService.class);
    private final AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
    private final ExecutionSteeringApplicationService service =
            new ExecutionSteeringApplicationService(steeringService, agentExecutionMapper);

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
        SecurityContextHolder.clearContext();
    }

    private AgentExecution executionOf(String tenantId, String createdBy) {
        AgentExecution execution = new AgentExecution();
        execution.setId("exec-1");
        execution.setTenantId(tenantId);
        execution.setCreatedBy(createdBy);
        return execution;
    }

    @Test
    void shouldAllowExecutionOwnerToSteer() {
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(executionOf("tenant-1", "user-1"));
        when(steeringService.create(any(), any(), any())).thenReturn(new ExecutionSteering());
        SecurityUtil.setCurrentUserId("user-1");

        service.steer("exec-1", "tenant-1", "请补充预算信息");

        verify(steeringService).create("tenant-1", "exec-1", "请补充预算信息");
    }

    @Test
    void shouldRejectSameTenantNonOwner() {
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(executionOf("tenant-1", "user-1"));
        SecurityUtil.setCurrentUserId("user-2");

        assertThatThrownBy(() -> service.steer("exec-1", "tenant-1", "干扰他人执行"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅执行所有者可投递引导");
        verify(steeringService, never()).create(any(), any(), any());
    }

    @Test
    void shouldRejectCrossTenantSteering() {
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(executionOf("tenant-1", "user-1"));
        SecurityUtil.setCurrentUserId("user-1");

        assertThatThrownBy(() -> service.steer("exec-1", "tenant-2", "跨租户投递"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("归属校验失败");
        verify(steeringService, never()).create(any(), any(), any());
    }

    @Test
    void shouldAllowSuperAdminToSteerAnyExecution() {
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(executionOf("tenant-1", "user-1"));
        when(steeringService.create(any(), any(), any())).thenReturn(new ExecutionSteering());
        SecurityUtil.setCurrentUserId("admin-1");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin-1", null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + CommonConstant.ROLE_SUPER_ADMIN))));

        service.steer("exec-1", "tenant-1", "超管介入引导");

        verify(steeringService).create("tenant-1", "exec-1", "超管介入引导");
    }

    @Test
    void shouldRejectSteeringForOwnerlessExecutionByNormalUser() {
        // 系统创建的无主执行（createdBy=null）仅超管可投递
        when(agentExecutionMapper.selectById("exec-1")).thenReturn(executionOf("tenant-1", null));
        SecurityUtil.setCurrentUserId("user-1");

        assertThatThrownBy(() -> service.steer("exec-1", "tenant-1", "无主执行"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("仅执行所有者可投递引导");
    }
}
