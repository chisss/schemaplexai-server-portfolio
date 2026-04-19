package com.schemaplexai.service.workflow.runtime;

import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.service.integration.git.GitWorkspaceOrchestrator;
import com.schemaplexai.service.mq.message.WorkflowTriggerMessage;
import com.schemaplexai.service.workspace.WorkspaceSessionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpecWorkflowRuntimeServiceTest {

    @Test
    void shouldBuildDynamicWorkflowGoalFromCurrentSpecContext() {
        SpecDocumentMapper specDocumentMapper = mock(SpecDocumentMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        GitWorkspaceOrchestrator gitWorkspaceOrchestrator = mock(GitWorkspaceOrchestrator.class);

        SpecWorkflowRuntimeService service = new SpecWorkflowRuntimeService(
                specDocumentMapper,
                workspaceMapper,
                gitWorkspaceOrchestrator
        );

        Spec spec = new Spec();
        spec.setName("工作流引擎缺陷修复");
        spec.setDescription("修复 Agent 节点默认指令来源错误，并验证标准研发工作流能够完整流转。");
        spec.setWorkspaceIds(List.of("workspace-1"));
        spec.setJiraTicket("AIP-101");
        spec.setTargetBranch("feature/AIP-101");

        Workspace workspace = new Workspace();
        workspace.setId("workspace-1");
        workspace.setName("SchemaPlexAI 主工程");
        workspace.setSourceType("local");
        workspace.setLocalPath("/tmp/workspace");
        workspace.setDefaultBranch("main");
        when(workspaceMapper.selectById("workspace-1")).thenReturn(workspace);
        WorkspaceSessionService.WorkspaceSession session = WorkspaceSessionService.WorkspaceSession.builder()
                .sessionId("session-1")
                .branchName("feature/AIP-101")
                .worktreePath("/tmp/isolated-worktree")
                .build();
        when(gitWorkspaceOrchestrator.prepareIsolatedWorkspaceFromSource(
                "tenant-1",
                "workspace-1",
                "user-1",
                "req-1",
                "/tmp/workspace",
                "main",
                "feature/AIP-101"
        )).thenReturn(session);

        SpecDocument requirementsDoc = new SpecDocument();
        requirementsDoc.setContent("要求工作流节点之间正确传递消息，并在最终阶段生成 Markdown 技术文档。");
        when(specDocumentMapper.selectOne(any())).thenReturn(requirementsDoc);

        WorkflowTriggerMessage message = WorkflowTriggerMessage.builder()
                .specId("spec-1")
                .docType("requirements")
                .triggerType("spec-review")
                .tenantId("tenant-1")
                .triggerBy("user-1")
                .requestId("req-1")
                .triggeredAt(LocalDateTime.now())
                .build();

        Map<String, Object> variables = service.buildRuntimeVariables(spec, message);

        assertThat(variables.get("workflowGoal"))
                .asString()
                .contains("工作流引擎缺陷修复")
                .contains("SchemaPlexAI 主工程")
                .contains("feature/AIP-101")
                .contains("上游节点传递的信息")
                .doesNotContain("titanium-policy");
        assertThat(variables.get("workspacePath")).isEqualTo("/tmp/isolated-worktree");
        assertThat(variables.get("workspaceSessionId")).isEqualTo("session-1");
        assertThat(variables).doesNotContainKey("artifactDocType");
        assertThat(variables.get("artifactOutputPath")).isEqualTo("docs/AIP-101-technical-design.md");
    }

    @Test
    void shouldFallbackToSnapshotWorkspaceWhenLocalIsolationFails() {
        SpecDocumentMapper specDocumentMapper = mock(SpecDocumentMapper.class);
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        GitWorkspaceOrchestrator gitWorkspaceOrchestrator = mock(GitWorkspaceOrchestrator.class);

        SpecWorkflowRuntimeService service = new SpecWorkflowRuntimeService(
                specDocumentMapper,
                workspaceMapper,
                gitWorkspaceOrchestrator
        );

        Spec spec = new Spec();
        spec.setName("SchemaPlexAI 工作区回退验证");
        spec.setDescription("验证本地工作区在 Git 隔离失败时会回退到受控快照目录。");
        spec.setWorkspaceIds(List.of("workspace-2"));
        spec.setTargetBranch("main");

        Workspace workspace = new Workspace();
        workspace.setId("workspace-2");
        workspace.setName("SchemaPlexAI 本地源码");
        workspace.setSourceType("local");
        workspace.setLocalPath("/Users/demo/projects/AiWorkPlatform");
        workspace.setDefaultBranch("main");
        when(workspaceMapper.selectById("workspace-2")).thenReturn(workspace);
        when(gitWorkspaceOrchestrator.prepareIsolatedWorkspaceFromSource(
                "tenant-2",
                "workspace-2",
                "user-2",
                "req-2",
                "/Users/demo/projects/AiWorkPlatform",
                "main",
                "main"
        )).thenThrow(new IllegalStateException("Invalid remote: origin"));
        WorkspaceSessionService.WorkspaceSession snapshotSession = WorkspaceSessionService.WorkspaceSession.builder()
                .sessionId("snapshot-session")
                .branchName("main")
                .worktreePath("/tmp/managed-snapshot")
                .sessionMode(WorkspaceSessionService.SessionMode.SNAPSHOT_COPY)
                .build();
        when(gitWorkspaceOrchestrator.prepareSnapshotWorkspaceFromSource(
                "tenant-2",
                "workspace-2",
                "user-2",
                "req-2",
                "/Users/demo/projects/AiWorkPlatform",
                "main"
        )).thenReturn(snapshotSession);

        WorkflowTriggerMessage message = WorkflowTriggerMessage.builder()
                .specId("spec-2")
                .docType("requirements")
                .triggerType("spec-review")
                .tenantId("tenant-2")
                .triggerBy("user-2")
                .requestId("req-2")
                .triggeredAt(LocalDateTime.now())
                .build();

        Map<String, Object> variables = service.buildRuntimeVariables(spec, message);

        assertThat(variables.get("workspacePath")).isEqualTo("/tmp/managed-snapshot");
        assertThat(variables.get("workspaceSessionId")).isEqualTo("snapshot-session");
        assertThat(variables.get("workspaceBranch")).isEqualTo("main");
    }
}
