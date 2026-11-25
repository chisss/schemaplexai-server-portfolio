package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.TenantMapper;
import com.schemaplexai.dao.mapper.TenantRuntimePolicyMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.converter.TenantRuntimePolicyConverter;
import com.schemaplexai.model.dto.system.TenantRuntimePolicyUpdateRequest;
import com.schemaplexai.model.entity.TenantRuntimePolicy;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.service.agent.execution.SandboxPolicyResolver.SandboxPolicyChangedEvent;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.impl.TenantRuntimePolicyServiceImpl;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SandboxPolicyResolverTest {

    @Test
    void shouldRefreshCachedCommandsAfterTenantPolicyUpdate() {
        var cached = new TenantRuntimePolicy();
        cached.setTenantId("tenant-1");
        cached.setSandboxAllowedCommands(List.of("ls"));
        var current = new TenantRuntimePolicy();
        current.setTenantId("tenant-1");
        current.setSandboxAllowedCommands(List.of("ls"));
        var policies = mock(TenantRuntimePolicyMapper.class);
        when(policies.selectById("tenant-1")).thenReturn(cached, current);
        var workspacePaths = mock(WorkspacePathResolver.class);
        when(workspacePaths.getWorkspaceRoot()).thenReturn(Path.of("/data/workspaces"));
        var resolver = new SandboxPolicyResolver(policies, mock(AgentToolBindingMapper.class),
                mock(WorkspaceMapper.class), workspacePaths);
        var context = AgentExecutionContext.builder().tenantId("tenant-1").build();
        assertThat(resolver.resolve(null, context).getAllowedCommands()).containsExactly("ls");

        var publisher = mock(ApplicationEventPublisher.class);
        var service = new TenantRuntimePolicyServiceImpl(mock(TenantMapper.class), policies,
                mock(TenantRuntimePolicyConverter.class), mock(EntityValidator.class), publisher, workspacePaths);
        var request = new TenantRuntimePolicyUpdateRequest();
        request.setSandboxAllowedCommands(List.of("ls", "git"));
        service.update("tenant-1", request);

        var event = new SandboxPolicyChangedEvent("tenant-1");
        verify(publisher).publishEvent(event);
        assertThat(resolver.resolve(null, context).getAllowedCommands()).containsExactly("ls");
        resolver.onPolicyChanged(event);
        assertThat(resolver.resolve(null, context).getAllowedCommands()).containsExactlyInAnyOrder("ls", "git");
    }

    @Test
    void shouldPersistTheManagedWorkspaceRootOnFirstCommandEdit() {
        var policies = mock(TenantRuntimePolicyMapper.class);
        var converter = mock(TenantRuntimePolicyConverter.class);
        var workspacePaths = mock(WorkspacePathResolver.class);
        Path managedRoot = Path.of("/home/app/.schemaplexai/workspaces");
        when(workspacePaths.getWorkspaceRoot()).thenReturn(managedRoot);
        var service = new TenantRuntimePolicyServiceImpl(mock(TenantMapper.class), policies, converter,
                mock(EntityValidator.class), mock(ApplicationEventPublisher.class), workspacePaths);
        var request = new TenantRuntimePolicyUpdateRequest();
        request.setSandboxAllowedCommands(List.of("ls", "git"));

        var policy = ArgumentCaptor.forClass(TenantRuntimePolicy.class);
        service.getByTenantId("tenant-1");
        verify(converter).toVO(policy.capture());
        assertThat(policy.getValue().getWorkspaceRootPath()).isEqualTo(managedRoot.toString());

        service.update("tenant-1", request);
        verify(policies).insert(policy.capture());
        assertThat(policy.getValue().getWorkspaceRootPath()).isEqualTo(managedRoot.toString());
        assertThat(policy.getValue().getSandboxAllowedCommands()).containsExactly("ls", "git");
    }

    @Test
    void shouldKeepManagedWorkspaceRootAndLocalAliasTogether() {
        TenantRuntimePolicyMapper tenantRuntimePolicyMapper = mock(TenantRuntimePolicyMapper.class);
        AgentToolBindingMapper agentToolBindingMapper = mock(AgentToolBindingMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        WorkspacePathResolver workspacePathResolver = mock(WorkspacePathResolver.class);

        TenantRuntimePolicy runtimePolicy = new TenantRuntimePolicy();
        runtimePolicy.setTenantId("tenant-1");
        runtimePolicy.setWorkspaceRootPath("/Users/demo/projects/AiWorkPlatform");
        runtimePolicy.setSandboxProfile("standard");
        runtimePolicy.setSandboxAllowedCommands(List.of("ls", "cat", "grep"));
        when(tenantRuntimePolicyMapper.selectById("tenant-1")).thenReturn(runtimePolicy);

        Workspace workspace = new Workspace();
        workspace.setTenantId("tenant-1");
        workspace.setLocalPath("/Users/demo/projects/AiWorkPlatform");
        when(workspaceMapper.selectList(any())).thenReturn(List.of(workspace));

        Path managedRoot = Path.of("/managed/workspaces");
        Path snapshotPath = managedRoot.resolve("tenant-1/ws-1/.snapshots/request-1");
        when(workspacePathResolver.getWorkspaceRoot()).thenReturn(managedRoot);
        when(workspacePathResolver.validateWithinWorkspaceRoot(eq(snapshotPath.toString()))).thenReturn(snapshotPath);
        when(workspacePathResolver.validateWithinWorkspaceRoot(eq("/Users/demo/projects/AiWorkPlatform")))
                .thenThrow(new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径必须位于工作空间根目录内"));

        SandboxPolicyResolver resolver = new SandboxPolicyResolver(
                tenantRuntimePolicyMapper,
                agentToolBindingMapper,
                workspaceMapper,
                workspacePathResolver
        );
        ReflectionTestUtils.setField(resolver, "defaultAllowedCommandsConfig", "ls,cat,grep");
        resolver.init();

        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .inputContext(Map.of(
                        "workspacePath", snapshotPath.toString(),
                        "workspaceLocalPath", "/Users/demo/projects/AiWorkPlatform"
                ))
                .build();

        SandboxPolicy policy = resolver.resolve(null, context);

        assertThat(policy.getAllowedPathPrefixes()).contains(managedRoot, Path.of("/Users/demo/projects/AiWorkPlatform"));
        assertThat(policy.getDefaultWorkingDirectory()).isEqualTo(snapshotPath);
        assertThat(policy.getWorkspacePathAliases()).contains(Path.of("/Users/demo/projects/AiWorkPlatform"));
    }
}
