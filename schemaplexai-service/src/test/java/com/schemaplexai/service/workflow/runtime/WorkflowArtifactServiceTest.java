package com.schemaplexai.service.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.dto.spec.SpecDocumentRequest;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.service.artifact.ArtifactService;
import com.schemaplexai.service.integration.feishu.FeishuDocDeliveryService;
import com.schemaplexai.service.integration.git.GitOperationService;
import com.schemaplexai.service.spec.handler.SpecVersionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowArtifactServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExtractMarkdownBlockBeforePersistingArtifact() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-1");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specName", "工作流回归");
        variables.put("artifactDocType", "design");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "docs/output.md");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("doc_gen");
        nodeExecution.setNodeLabel("文档生成");

        String wrappedMarkdown = """
                基于当前已获取的信息，已生成文档，请审阅。

                ```markdown
                # 技术设计文档

                正文内容
                ```
                """;

        service.persistAgentArtifactIfNecessary(instance, nodeExecution, Map.of(), wrappedMarkdown);

        String saved = Files.readString(tempDir.resolve("docs/output.md"));
        assertThat(saved).startsWith("# 技术设计文档");
        assertThat(saved).contains("正文内容");
        assertThat(saved).doesNotContain("```markdown");
        assertThat(saved).doesNotContain("基于当前已获取的信息");
    }

    @Test
    void shouldKeepFullMarkdownDocumentWhenBodyContainsInnerCodeBlocks() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-2");
        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactDocType", "design");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("solution_design");
        nodeExecution.setNodeLabel("方案设计");

        String markdown = """
                # SchemaPlexAI Spec 工作流与 Agent 审核闭环方案设计

                ## 1. 背景
                当前页面需要展示完整的审批内容，而不是正文中的某个标题片段。

                ## 2. Prompt 示例
                ```markdown
                角色: 方案设计师
                目标: 输出完整设计文档
                ```

                ## 3. API 设计
                #### 2. 提交审核 (由前端在 Agent 产出后自动触发或手动触发)
                ```http
                POST /api/workflow/nodes/{nodeId}/complete
                ```

                ## 4. 验收建议
                保留全文，不要被内嵌代码块截断。
                """;

        service.persistAgentArtifactIfNecessary(instance, nodeExecution, Map.of("artifactDocType", "design"), markdown);

        ArgumentCaptor<SpecDocumentRequest> requestCaptor = ArgumentCaptor.forClass(SpecDocumentRequest.class);
        verify(specVersionHandler).saveDocument(
                org.mockito.ArgumentMatchers.eq("spec-2"),
                org.mockito.ArgumentMatchers.eq("design"),
                org.mockito.ArgumentMatchers.eq("solution_design"),
                requestCaptor.capture()
        );

        String savedContent = requestCaptor.getValue().getContent();
        assertThat(savedContent).startsWith("# SchemaPlexAI Spec 工作流与 Agent 审核闭环方案设计");
        assertThat(savedContent).contains("## 1. 背景");
        assertThat(savedContent).contains("#### 2. 提交审核 (由前端在 Agent 产出后自动触发或手动触发)");
        assertThat(savedContent).contains("## 4. 验收建议");
    }

    @Test
    void shouldInferImplementationArtifactPathFromDocType() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-impl");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specName", "实现阶段回归");
        variables.put("jiraTicket", "AIP-202");
        variables.put("workspacePath", tempDir.toString());
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("code_development");
        nodeExecution.setNodeLabel("代码开发");

        service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of("artifactDocType", "implementation"),
                "# 实现报告\n\n- 已完成控制器修复\n"
        );

        assertThat(Files.readString(tempDir.resolve("docs/AIP-202-implementation.md")))
                .startsWith("# 实现报告");
        verify(specVersionHandler).saveDocument(
                eq("spec-impl"),
                eq("implementation"),
                eq("code_development"),
                any(SpecDocumentRequest.class)
        );
    }

    @Test
    void shouldPersistMarketingBundleWithPrimaryVariantAsArtifactContent() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.saveWorkflowArtifact(any()))
                .thenReturn(new ArtifactService.WorkflowArtifactPersistResult("artifact-marketing", 1));

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-marketing");
        instance.setSpecId("spec-marketing");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specName", "营销回归");
        variables.put("specType", "marketing");
        variables.put("workspaceId", "workspace-1");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "marketing/20260411/");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("marketing_copy_team");
        nodeExecution.setNodeLabel("营销文案 Team Agent");

        String result = """
                {
                  "summary": "已生成营销文案产物包",
                  "bundleItems": [
                    {
                      "variantKey": "A",
                      "title": "营销文案终稿",
                      "content": "# 营销文案终稿\\n\\n## 已确认事实\\n\\n- 目标市场：菲律宾\\n"
                    }
                  ]
                }
                """;

        service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of("artifactType", "marketing_copy_bundle"),
                result,
                Map.of(
                        "agentModel", "MiniMax-M2.7",
                        "agentExecutionId", "exec-marketing",
                        "runtimeEngine", "team_langgraph4j",
                        "qualityScore", 85
                )
        );

        assertThat(Files.readString(tempDir.resolve("marketing/20260411/variant-a.md")))
                .startsWith("# 营销文案终稿");
        assertThat(Files.readString(tempDir.resolve("marketing/20260411/README.md")))
                .contains("## 产物概览")
                .contains("## AB 变体");

        ArgumentCaptor<ArtifactService.WorkflowArtifactPersistCommand> commandCaptor =
                ArgumentCaptor.forClass(ArtifactService.WorkflowArtifactPersistCommand.class);
        verify(artifactService).saveWorkflowArtifact(commandCaptor.capture());

        ArtifactService.WorkflowArtifactPersistCommand command = commandCaptor.getValue();
        assertThat(command.contentText())
                .startsWith("# 营销文案终稿")
                .doesNotContain("## 已确认运行时元数据");
        assertThat(String.valueOf(command.metadataJson().get("bundleSummaryMarkdown")))
                .contains("## 产物概览");
        assertThat(command.workspaceTargets().stream().map(ArtifactService.WorkspaceDeliveryTarget::targetPath).toList())
                .containsExactly("marketing/20260411/variant-a.md", "marketing/20260411/README.md");
    }

    @Test
    void shouldStripMarketingLeadWrapperAndKeepFullVariantContent() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.saveWorkflowArtifact(any()))
                .thenReturn(new ArtifactService.WorkflowArtifactPersistResult("artifact-marketing-clean", 1));

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-marketing-clean");
        instance.setSpecId("spec-marketing-clean");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specName", "营销终稿清洗");
        variables.put("specType", "marketing");
        variables.put("workspaceId", "workspace-1");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "marketing/20260411/");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("marketing_copy_team");
        nodeExecution.setNodeLabel("营销文案 Team Agent");

        String result = """
                {
                  "summary": "已生成营销文案产物包",
                  "bundleItems": [
                    {
                      "variantKey": "A",
                      "title": "菲律宾信贷系统营销文案包",
                      "content": "# 菲律宾信贷系统营销文案包\\n\\nBased on the upstream agent outputs already provided in the context, I will now consolidate and deliver the final marketing package as Team Leader.\\n\\n---\\n\\n# 菲律宾信贷产品营销文案交付包\\n\\n## 已确认事实\\n\\n- 目标市场：菲律宾\\n\\n## 合规风险提示\\n\\n- disclose APR\\n"
                    }
                  ]
                }
                """;

        service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of("artifactType", "marketing_copy_bundle"),
                result,
                Map.of()
        );

        String saved = Files.readString(tempDir.resolve("marketing/20260411/variant-a.md"));
        assertThat(saved)
                .startsWith("# 菲律宾信贷产品营销文案交付包")
                .contains("## 已确认事实")
                .contains("## 合规风险提示")
                .doesNotContain("Based on the upstream agent outputs")
                .doesNotContain("# 菲律宾信贷系统营销文案包");

        ArgumentCaptor<ArtifactService.WorkflowArtifactPersistCommand> commandCaptor =
                ArgumentCaptor.forClass(ArtifactService.WorkflowArtifactPersistCommand.class);
        verify(artifactService).saveWorkflowArtifact(commandCaptor.capture());

        assertThat(commandCaptor.getValue().contentText())
                .startsWith("# 菲律宾信贷产品营销文案交付包")
                .contains("## 合规风险提示")
                .doesNotContain("Based on the upstream agent outputs");
    }

    @Test
    void shouldInjectRuntimeMetadataBlockIntoArtifactContent() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-9");
        instance.setSpecId("spec-9");
        instance.setVariables(new HashMap<>());

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("team_agent");
        nodeExecution.setNodeLabel("Team Agent 执行");

        SpecDocument savedDocument = new SpecDocument();
        savedDocument.setId("doc-9");
        savedDocument.setVersion(1);
        when(specVersionHandler.saveDocument(
                org.mockito.ArgumentMatchers.eq("spec-9"),
                org.mockito.ArgumentMatchers.eq("requirements"),
                org.mockito.ArgumentMatchers.eq("team_agent"),
                org.mockito.ArgumentMatchers.any(SpecDocumentRequest.class)
        )).thenReturn(savedDocument);

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of("artifactDocType", "requirements"),
                "# Team Agent 测试结果\n\n- 已完成主链路验证\n",
                Map.of(
                        "agentModel", "MiniMax-M2.7",
                        "agentExecutionId", "exec-9",
                        "runtimeEngine", "team_langgraph4j",
                        "qualityScore", 88,
                        "qualityTaskId", "quality-9",
                        "qualityCheckedAt", "2026-04-11T00:33:16"
                )
        );

        assertThat(String.valueOf(artifact.get("artifactContent")))
                .contains("## 已确认运行时元数据")
                .contains("| 模型名 | MiniMax-M2.7 |")
                .contains("| 执行 ID | exec-9 |")
                .contains("| 文档版本 | v1 |")
                .contains("| 质量分 | 88 |");
        assertThat(artifact)
                .containsEntry("artifactDocId", "doc-9")
                .containsEntry("artifactDocVersion", 1);
    }

    @Test
    void shouldSkipRuntimeMetadataForCustomerDeliveryArtifact() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-delivery");
        instance.setName("客户交付闭环");
        instance.setVariables(new HashMap<>());

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("scene_delivery_team");
        nodeExecution.setNodeLabel("方案交付");

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(
                        "artifactDocType", "delivery",
                        "artifactOutputPath", "deliveries/blogger/customer-demo.md",
                        "artifactTitle", "SchemaPlexAI 知识内容生产客户演示方案"
                ),
                """
                # SchemaPlexAI 知识内容生产客户演示方案

                ## 已确认事实

                - 已完成客户诊断
                """,
                Map.of(
                        "agentModel", "Claude Code Sonnet 4.6",
                        "agentExecutionId", "exec-delivery",
                        "runtimeEngine", "team_langgraph4j",
                        "qualityScore", 95
                )
        );

        assertThat(String.valueOf(artifact.get("artifactContent")))
                .startsWith("# SchemaPlexAI 知识内容生产客户演示方案")
                .contains("## 已确认事实")
                .doesNotContain("## 已确认运行时元数据")
                .doesNotContain("| 工作流实例 ID |");
    }

    @Test
    void shouldPrependPrimaryTitleWhenArtifactStartsWithSecondaryHeading() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-10");
        instance.setSpecId("spec-10");
        instance.setVariables(new HashMap<>());

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("team_agent");
        nodeExecution.setNodeLabel("Team Agent 执行");

        SpecDocument savedDocument = new SpecDocument();
        savedDocument.setId("doc-10");
        savedDocument.setVersion(1);
        when(specVersionHandler.saveDocument(
                org.mockito.ArgumentMatchers.eq("spec-10"),
                org.mockito.ArgumentMatchers.eq("requirements"),
                org.mockito.ArgumentMatchers.eq("team_agent"),
                org.mockito.ArgumentMatchers.any(SpecDocumentRequest.class)
        )).thenReturn(savedDocument);

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of("artifactDocType", "requirements"),
                "## Team 聚合元数据\n\n- 成员总数: 2\n\n[Lead Planner]\n结论已生成。\n",
                Map.of(
                        "agentModel", "MiniMax-M2.7",
                        "agentExecutionId", "exec-10",
                        "runtimeEngine", "team_langgraph4j",
                        "qualityScore", 91
                )
        );

        assertThat(String.valueOf(artifact.get("artifactContent")))
                .startsWith("# Team Agent 执行\n\n## 已确认运行时元数据")
                .contains("## Team 聚合元数据")
                .contains("| 模型名 | MiniMax-M2.7 |")
                .contains("| 执行 ID | exec-10 |");
    }

    @Test
    void shouldPreferWorkspaceArtifactContentOverExecutionSummary() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(
                tempDir.resolve("docs/output.md"),
                """
                # 完整需求分析文档

                ## 当前仓库已确认现状
                - 已读取真实 Controller 与 Liquibase 脚本。
                """
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-3");
        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactDocType", "requirements");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "docs/output.md");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("requirements_analysis");
        nodeExecution.setNodeLabel("需求分析");

        String resultSummary = """
                ## 需求分析文档生成完成

                **输出路径**: `docs/output.md`

                已完成真实采证。
                """;

        service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of("artifactDocType", "requirements"),
                resultSummary
        );

        ArgumentCaptor<SpecDocumentRequest> requestCaptor = ArgumentCaptor.forClass(SpecDocumentRequest.class);
        verify(specVersionHandler).saveDocument(
                org.mockito.ArgumentMatchers.eq("spec-3"),
                org.mockito.ArgumentMatchers.eq("requirements"),
                org.mockito.ArgumentMatchers.eq("requirements_analysis"),
                requestCaptor.capture()
        );

        String savedContent = requestCaptor.getValue().getContent();
        assertThat(savedContent).startsWith("# 完整需求分析文档");
        assertThat(savedContent).contains("当前仓库已确认现状");
        assertThat(savedContent).doesNotContain("需求分析文档生成完成");
    }

    @Test
    void shouldNotReuseGlobalArtifactPathForNonDocumentAgentNodes() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(
                tempDir.resolve("docs/output.md"),
                """
                # 旧版方案设计文档

                这是上一个节点写入的设计内容。
                """
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-4");
        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactOutputPath", "docs/output.md");
        variables.put("workspacePath", tempDir.toString());
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("task_breakdown");
        nodeExecution.setNodeLabel("任务拆分");

        String markdown = """
                # 任务拆分文档

                - T1: 补充接口
                - T2: 增加回归测试
                """;

        service.persistAgentArtifactIfNecessary(instance, nodeExecution, Map.of("artifactDocType", "tasks"), markdown);

        ArgumentCaptor<SpecDocumentRequest> requestCaptor = ArgumentCaptor.forClass(SpecDocumentRequest.class);
        verify(specVersionHandler).saveDocument(
                org.mockito.ArgumentMatchers.eq("spec-4"),
                org.mockito.ArgumentMatchers.eq("tasks"),
                org.mockito.ArgumentMatchers.eq("task_breakdown"),
                requestCaptor.capture()
        );

        String savedContent = requestCaptor.getValue().getContent();
        assertThat(savedContent).startsWith("# 任务拆分文档");
        assertThat(savedContent).contains("T1: 补充接口");
        assertThat(savedContent).doesNotContain("旧版方案设计文档");
    }

    @Test
    void shouldSanitizeInternalToolBlocksFromWorkspaceArtifactContent() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        Files.createDirectories(tempDir.resolve("docs"));
        Files.writeString(
                tempDir.resolve("docs/output.md"),
                """
                <minimax:tool_call>
                <invoke name="仓库检索工具">
                <parameter name="path">titanium-policy-api/src/main/java/com/titanium/policy/api/PolicyChangePreviewApi.java</parameter>
                <parameter name="workdir">/Users/demo/.schemaplexai/workspaces/runtime</parameter>
                </invoke>
                </minimax:tool_call>

                # 任务拆分文档

                - T1: 保持既有任务结构
                - T2: 补充回归验证
                """
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-5");
        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactDocType", "tasks");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "docs/output.md");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("task_breakdown");
        nodeExecution.setNodeLabel("任务拆分");

        String resultSummary = """
                ## 任务拆分完成

                **输出路径**: `docs/output.md`
                """;

        service.persistAgentArtifactIfNecessary(instance, nodeExecution, Map.of("artifactDocType", "tasks"), resultSummary);

        ArgumentCaptor<SpecDocumentRequest> requestCaptor = ArgumentCaptor.forClass(SpecDocumentRequest.class);
        verify(specVersionHandler).saveDocument(
                org.mockito.ArgumentMatchers.eq("spec-5"),
                org.mockito.ArgumentMatchers.eq("tasks"),
                org.mockito.ArgumentMatchers.eq("task_breakdown"),
                requestCaptor.capture()
        );

        String savedContent = requestCaptor.getValue().getContent();
        assertThat(savedContent).startsWith("# 任务拆分文档");
        assertThat(savedContent).contains("T1: 保持既有任务结构");
        assertThat(savedContent).doesNotContain("<minimax:tool_call>");
        assertThat(savedContent).doesNotContain("<invoke");
        assertThat(savedContent).doesNotContain("/Users/demo/.schemaplexai/workspaces/runtime");
    }

    @Test
    void shouldIgnoreInheritedArtifactDocTypeForNonDocumentNodes() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-6");
        Map<String, Object> variables = new HashMap<>();
        variables.put("artifactDocType", "design");
        variables.put("artifactOutputPath", "docs/SPAI-xxx-technical-design.md");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("code_development");
        nodeExecution.setNodeLabel("代码开发");

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(),
                "# 代码开发结项报告\n\n- 已完成接口定义\n"
        );

        assertThat(artifact).isEmpty();
        verifyNoInteractions(specVersionHandler, specMapper, gitOperationService, artifactService);
    }

    @Test
    void shouldForceIncludeArtifactPathWhenCommittingWorkspaceArtifact() throws Exception {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();

        WorkflowArtifactService service = newService(specMapper, specVersionHandler, gitOperationService, artifactService);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setSpecId("spec-7");
        Map<String, Object> variables = new HashMap<>();
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "docs/output.md");
        variables.put("jiraTicket", "REALTEST-20260409");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("doc_gen");
        nodeExecution.setNodeLabel("文档生成");

        service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(),
                "# 技术实现与验证报告\n\n- 已生成文档\n"
        );

        verify(gitOperationService).commitChanges(
                tempDir.toString(),
                "docs: generate workflow artifact for REALTEST-20260409",
                List.of("docs/output.md")
        );
    }

    @Test
    void shouldPublishMarketingBundleToFeishuDocWhenDeliveryConfigured() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        FeishuDocDeliveryService feishuDocDeliveryService = mock(FeishuDocDeliveryService.class);

        WorkflowArtifactService service = newService(
                specMapper,
                specVersionHandler,
                gitOperationService,
                artifactService,
                notificationChannelMapper,
                feishuDocDeliveryService
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-1");
        channel.setName("飞书机器人通知");
        channel.setChannelType("feishu");
        channel.setStatus("active");
        channel.setConfig(Map.of(
                "app_id", "cli_mock",
                "app_secret", "mock-secret"
        ));
        when(notificationChannelMapper.selectById("channel-1")).thenReturn(channel);
        when(feishuDocDeliveryService.deliver(any(), any(), any()))
                .thenReturn(new FeishuDocDeliveryService.FeishuDocDeliveryResult(
                        "doxcnFeishuDoc123",
                        "https://feishu.cn/docx/doxcnFeishuDoc123",
                        "菲律宾信贷系统营销文案包",
                        3,
                        7
                ));

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-feishu-doc");
        instance.setSpecId("spec-feishu-doc");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specType", "marketing");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "marketing/20260412/");
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("marketing_copy_team");
        nodeExecution.setNodeLabel("营销文案 Team Agent");

        String result = """
                {
                  "summary": "已生成菲律宾信贷系统 AB 版营销文案",
                  "complianceNotes": "需披露 APR 与放款条件。",
                  "bundleItems": [
                    {
                      "variantKey": "A",
                      "title": "利益导向版",
                      "content": "# 利益导向版\\n\\n- 最快 5 分钟完成申请\\n"
                    },
                    {
                      "variantKey": "B",
                      "title": "信任导向版",
                      "content": "# 信任导向版\\n\\n- 透明披露费用结构\\n"
                    }
                  ]
                }
                """;

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(
                        "artifactType", "marketing_copy_bundle",
                        "artifactTitle", "菲律宾信贷系统营销文案包",
                        "artifactDeliveryType", "feishu_doc",
                        "artifactDeliveryChannelId", "channel-1"
                ),
                result
        );

        assertThat(artifact.get("artifactDeliveryType")).isEqualTo("feishu_doc");
        assertThat(artifact.get("artifactDeliveryUrl")).isEqualTo("https://feishu.cn/docx/doxcnFeishuDoc123");
        assertThat(artifact.get("artifactDeliveryDocumentId")).isEqualTo("doxcnFeishuDoc123");

        verify(feishuDocDeliveryService).deliver(
                eq(channel.getConfig()),
                eq("菲律宾信贷系统营销文案包"),
                contains("## 文案详情")
        );

        ArgumentCaptor<ArtifactService.WorkflowArtifactPersistCommand> commandCaptor =
                ArgumentCaptor.forClass(ArtifactService.WorkflowArtifactPersistCommand.class);
        verify(artifactService).saveWorkflowArtifact(commandCaptor.capture());
        assertThat(commandCaptor.getValue().workspaceTargets().stream()
                .map(ArtifactService.WorkspaceDeliveryTarget::deliveryType)
                .toList()).contains("feishu_doc");
    }

    @Test
    void shouldPublishFeishuDocForNonSpecWorkflowInstance() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        FeishuDocDeliveryService feishuDocDeliveryService = mock(FeishuDocDeliveryService.class);

        WorkflowArtifactService service = newService(
                specMapper,
                specVersionHandler,
                gitOperationService,
                artifactService,
                notificationChannelMapper,
                feishuDocDeliveryService
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-non-spec");
        channel.setName("飞书机器人通知");
        channel.setChannelType("feishu");
        channel.setStatus("active");
        channel.setConfig(Map.of(
                "app_id", "cli_mock",
                "app_secret", "mock-secret"
        ));
        when(notificationChannelMapper.selectById("channel-non-spec")).thenReturn(channel);
        when(feishuDocDeliveryService.deliver(any(), any(), any()))
                .thenReturn(new FeishuDocDeliveryService.FeishuDocDeliveryResult(
                        "doxcnNonSpec123",
                        "https://feishu.cn/docx/doxcnNonSpec123",
                        "SchemaPlexAI 门店经营演示交付",
                        2,
                        5
                ));

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-non-spec");
        instance.setSpecId(null);
        instance.setTenantId("tenant-non-spec");
        instance.setVariables(new HashMap<>(Map.of(
                "workspaceId", "workspace-1",
                "workspacePath", tempDir.toString(),
                "artifactOutputPath", "marketing/store/",
                "specType", "marketing"
        )));

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("delivery_team");
        nodeExecution.setNodeLabel("客户交付 Team");

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(
                        "artifactType", "marketing_copy_bundle",
                        "artifactTitle", "SchemaPlexAI 门店经营演示交付",
                        "artifactDeliveryType", "feishu_doc",
                        "artifactDeliveryChannelId", "channel-non-spec"
                ),
                """
                        {
                          "summary": "已生成门店经营闭环交付包",
                          "bundleItems": [
                            {
                              "variantKey": "A",
                              "title": "门店经营闭环交付",
                              "content": "# 门店经营闭环交付\\n\\n## 已确认事实\\n\\n- 客户：上海徐汇美甲工作室\\n"
                            }
                          ]
                        }
                        """
        );

        assertThat(artifact.get("artifactDeliveryType")).isEqualTo("feishu_doc");
        assertThat(artifact.get("artifactDeliveryUrl")).isEqualTo("https://feishu.cn/docx/doxcnNonSpec123");
        assertThat(artifact.get("artifactDeliveryDocumentId")).isEqualTo("doxcnNonSpec123");

        ArgumentCaptor<ArtifactService.WorkflowArtifactPersistCommand> commandCaptor =
                ArgumentCaptor.forClass(ArtifactService.WorkflowArtifactPersistCommand.class);
        verify(artifactService).saveWorkflowArtifact(commandCaptor.capture());
        assertThat(commandCaptor.getValue().specId()).isNull();
        assertThat(commandCaptor.getValue().tenantId()).isEqualTo("tenant-non-spec");
        assertThat(commandCaptor.getValue().workspaceTargets().stream()
                .map(ArtifactService.WorkspaceDeliveryTarget::deliveryType)
                .toList()).contains("feishu_doc");
        verifyNoInteractions(specVersionHandler);
    }

    @Test
    void shouldOverrideFeishuDeliveryConfigFromMarketingProfile() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        FeishuDocDeliveryService feishuDocDeliveryService = mock(FeishuDocDeliveryService.class);

        WorkflowArtifactService service = newService(
                specMapper,
                specVersionHandler,
                gitOperationService,
                artifactService,
                notificationChannelMapper,
                feishuDocDeliveryService
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-profile");
        channel.setName("飞书文档渠道");
        channel.setChannelType("feishu");
        channel.setStatus("active");
        channel.setConfig(new LinkedHashMap<>(Map.of(
                "app_id", "cli_profile",
                "app_secret", "profile-secret",
                "document_folder_token", "folder-default"
        )));
        when(notificationChannelMapper.selectById("channel-profile")).thenReturn(channel);
        when(feishuDocDeliveryService.deliver(any(), any(), any()))
                .thenReturn(new FeishuDocDeliveryService.FeishuDocDeliveryResult(
                        "doxcnProfileDoc",
                        "https://feishu.cn/docx/doxcnProfileDoc",
                        "Spec 级飞书交付标题",
                        4,
                        6
                ));

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-profile");
        instance.setSpecId("spec-profile");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specType", "marketing");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "marketing/20260412/");
        variables.put("profileData", Map.of(
                "deliveryConfig", Map.of(
                        "deliveryType", "feishu_doc",
                        "channelId", "channel-profile",
                        "title", "Spec 级飞书交付标题",
                        "folderToken", "folder-override"
                )
        ));
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("marketing_copy_team");
        nodeExecution.setNodeLabel("营销文案 Team Agent");

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(
                        "artifactType", "marketing_copy_bundle",
                        "artifactTitle", "默认营销文案标题",
                        "artifactDeliveryType", "workspace_file"
                ),
                """
                        {
                          "summary": "已生成营销文案",
                          "bundleItems": [
                            {
                              "variantKey": "A",
                              "title": "A版",
                              "content": "# A版\\n\\n- 首次借款用户\\n"
                            }
                          ]
                        }
                        """
        );

        assertThat(artifact.get("artifactDeliveryType")).isEqualTo("feishu_doc");
        assertThat(artifact.get("artifactDeliveryUrl")).isEqualTo("https://feishu.cn/docx/doxcnProfileDoc");
        assertThat(artifact.get("artifactDeliveryDocumentId")).isEqualTo("doxcnProfileDoc");

        ArgumentCaptor<Map<String, Object>> configCaptor = ArgumentCaptor.forClass(Map.class);
        verify(feishuDocDeliveryService).deliver(configCaptor.capture(), eq("Spec 级飞书交付标题"), contains("## 文案详情"));
        assertThat(configCaptor.getValue()).containsEntry("document_folder_token", "folder-override");
    }

    @Test
    void shouldSkipFeishuDeliveryWhenMarketingProfileChoosesWorkspaceOnly() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        GitOperationService gitOperationService = mock(GitOperationService.class);
        ArtifactService artifactService = mockArtifactService();
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        FeishuDocDeliveryService feishuDocDeliveryService = mock(FeishuDocDeliveryService.class);

        WorkflowArtifactService service = newService(
                specMapper,
                specVersionHandler,
                gitOperationService,
                artifactService,
                notificationChannelMapper,
                feishuDocDeliveryService
        );

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-workspace-only");
        instance.setSpecId("spec-workspace-only");
        Map<String, Object> variables = new HashMap<>();
        variables.put("specType", "marketing");
        variables.put("workspacePath", tempDir.toString());
        variables.put("artifactOutputPath", "marketing/20260412/");
        variables.put("profileData", Map.of(
                "deliveryConfig", Map.of(
                        "deliveryType", "workspace_file"
                )
        ));
        instance.setVariables(variables);

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setNodeId("marketing_copy_team");
        nodeExecution.setNodeLabel("营销文案 Team Agent");

        Map<String, Object> artifact = service.persistAgentArtifactIfNecessary(
                instance,
                nodeExecution,
                Map.of(
                        "artifactType", "marketing_copy_bundle",
                        "artifactTitle", "默认营销文案标题",
                        "artifactDeliveryType", "feishu_doc",
                        "artifactDeliveryChannelId", "channel-default"
                ),
                """
                        {
                          "summary": "已生成营销文案",
                          "bundleItems": [
                            {
                              "variantKey": "A",
                              "title": "A版",
                              "content": "# A版\\n\\n- 透明审批\\n"
                            }
                          ]
                        }
                        """
        );

        assertThat(artifact).doesNotContainKeys(
                "artifactDeliveryType",
                "artifactDeliveryChannelId",
                "artifactDeliveryUrl",
                "artifactDeliveryDocumentId"
        );
        verifyNoInteractions(notificationChannelMapper, feishuDocDeliveryService);
    }

    private ArtifactService mockArtifactService() {
        ArtifactService artifactService = mock(ArtifactService.class);
        when(artifactService.saveWorkflowArtifact(any()))
                .thenReturn(new ArtifactService.WorkflowArtifactPersistResult("artifact-1", 1));
        return artifactService;
    }

    private WorkflowArtifactService newService(SpecMapper specMapper,
                                               SpecVersionHandler specVersionHandler,
                                               GitOperationService gitOperationService,
                                               ArtifactService artifactService) {
        return newService(
                specMapper,
                specVersionHandler,
                gitOperationService,
                artifactService,
                mock(NotificationChannelMapper.class),
                mock(FeishuDocDeliveryService.class)
        );
    }

    private WorkflowArtifactService newService(SpecMapper specMapper,
                                               SpecVersionHandler specVersionHandler,
                                               GitOperationService gitOperationService,
                                               ArtifactService artifactService,
                                               NotificationChannelMapper notificationChannelMapper,
                                               FeishuDocDeliveryService feishuDocDeliveryService) {
        return new WorkflowArtifactService(
                specMapper,
                specVersionHandler,
                gitOperationService,
                artifactService,
                new ObjectMapper(),
                notificationChannelMapper,
                feishuDocDeliveryService
        );
    }
}
