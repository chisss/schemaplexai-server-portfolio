package com.schemaplexai.service.agent.execution;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.TenantRuntimePolicyMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.entity.TenantRuntimePolicy;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.service.workspace.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SandboxPolicyResolverTest {

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
