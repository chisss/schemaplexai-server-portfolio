package com.schemaplexai.service.workflow.engine.assembler;

import com.schemaplexai.model.entity.WorkflowInstance;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowNodeContextAssemblerTest {

    private final WorkflowNodeContextAssembler assembler = new WorkflowNodeContextAssembler();

    @Test
    void shouldUseUpstreamResultAsAgentInstructionByDefault() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of("instructionSource", "upstream");
        Map<String, Object> inputData = Map.of(
                "result", "请继续基于上一节点输出完善技术设计文档",
                "_instanceId", "wf-1"
        );

        String prompt = assembler.buildAgentExecutionPrompt(instance, "文档生成", config, inputData);

        assertThat(prompt).contains("请继续基于上一节点输出完善技术设计文档");
        assertThat(prompt).contains("## 当前流程上下文");
    }

    @Test
    void shouldAppendModifyInstructionWhenManualTaskInstructionConfigured() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of(
                "instructionSource", "manual",
                "taskInstruction", "请基于已审核通过的需求和设计完成代码开发，并输出实现摘要。"
        );
        Map<String, Object> inputData = Map.of(
                "modifyInstruction", "请补充接口兼容策略，并明确受影响的数据库字段。",
                "result", "上一轮代码开发结果摘要"
        );

        String prompt = assembler.buildAgentExecutionPrompt(instance, "代码开发", config, inputData);

        assertThat(prompt).contains("请基于已审核通过的需求和设计完成代码开发，并输出实现摘要。");
        assertThat(prompt).contains("## 本轮修改要求");
        assertThat(prompt).contains("请补充接口兼容策略，并明确受影响的数据库字段。");
        assertThat(prompt).contains("## 参考上游产物");
        assertThat(prompt).contains("上一轮代码开发结果摘要");
    }

    @Test
    void shouldKeepLegacyManualInstructionWhenSourceIsMissing() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of("taskInstruction", "请手动执行特定领域分析");

        String prompt = assembler.buildAgentExecutionPrompt(instance, "需求分析", config, Map.of());

        assertThat(prompt).startsWith("请手动执行特定领域分析");
    }

    @Test
    void shouldFallbackToWorkflowGoalWhenNoUpstreamInstructionExists() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of("instructionSource", "upstream");

        String prompt = assembler.buildAgentExecutionPrompt(instance, "测试规划", config, Map.of("triggered", true));

        assertThat(prompt).contains("流程目标");
        assertThat(prompt).contains("测试规划");
    }

    @Test
    void shouldIgnoreAutoPlaceholderAndFallbackToWorkflowGoal() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of("instructionSource", "upstream");

        String prompt = assembler.buildAgentExecutionPrompt(
                instance,
                "南美氨基酸原料获客 Team Agent",
                config,
                Map.of("summary", "{auto=true}", "nextTaskInstruction", "{auto=true}")
        );

        assertThat(prompt).contains("请围绕以下流程目标完成当前节点[南美氨基酸原料获客 Team Agent]");
        assertThat(prompt).contains("输出完整的技术设计与测试规划文档");
        assertThat(prompt).doesNotContain("{auto=true}");
    }

    @Test
    void shouldResolveNodeSpecificArtifactPathForRequirementsNode() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of(
                "instructionSource", "manual",
                "taskInstruction", "请产出需求分析文档",
                "artifactDocType", "requirements"
        );

        String prompt = assembler.buildAgentExecutionPrompt(instance, "需求分析", config, Map.of("triggered", true));

        assertThat(prompt).contains("目标产物: docs/AIP-101-requirements.md");
        assertThat(prompt).doesNotContain("docs/AIP-101-technical-design.md");
        assertThat(prompt).contains("当前仓库已确认现状");
        assertThat(prompt).contains("建议改造/待实现项");
        assertThat(prompt).contains("工作目录（sys.* 工具 workdir）");
        assertThat(prompt).contains("所有 sys.read、sys.grep、sys.ls、sys.bash 等 sys.* 工具调用都必须显式传入 workdir");
        assertThat(prompt).contains("sys.read 或 sys.grep");
        assertThat(prompt).contains("仓库中未发现");
        assertThat(prompt).contains("建议首批采证动作");
        assertThat(prompt).contains("@RestController|@RequestMapping|@PostMapping|@GetMapping|@PutMapping|@DeleteMapping");
        assertThat(prompt).contains("*-application/src/main/java");
        assertThat(prompt).contains("liquibase/*.sql");
    }

    @Test
    void shouldInjectSystemConfirmedFactsIntoPromptWhenAgentFactsExist() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of(
                "instructionSource", "upstream",
                "artifactDocType", "requirements",
                "boundAgentName", "Team Agent E2E",
                "boundAgentType", "team",
                "boundAgentStatus", "active",
                "boundAgentModel", "MiniMax-M2.7",
                "boundRuntimeEngine", "team_langgraph4j",
                "maxRounds", 8,
                "maxToolCallsPerRound", 4
        );

        String prompt = assembler.buildAgentExecutionPrompt(
                instance,
                "Team Agent 执行",
                config,
                Map.of("result", "请基于上游输入输出测试结论")
        );

        assertThat(prompt).contains("系统已确认事实");
        assertThat(prompt).contains("绑定Agent主模型: MiniMax-M2.7");
        assertThat(prompt).contains("绑定运行时引擎: team_langgraph4j");
        assertThat(prompt).contains("禁止继续写成“待确认”“可能”“推测”");
        assertThat(prompt).contains("系统会在文档落库前回填模型名、执行ID、质量分、文档版本");
    }

    @Test
    void shouldAddImplementationExecutionHintsForCodeDevelopmentNode() {
        WorkflowInstance instance = buildInstance();
        instance.getVariables().put("codeDevelopmentSummary", "已修改 PolicyController.java 并补充 WorkflowController.java");
        Map<String, Object> config = Map.of(
                "instructionSource", "manual",
                "taskInstruction", "请完成真实代码修改并输出实现总结。",
                "artifactDocType", "implementation",
                "outputVariableKey", "codeDevelopmentSummary"
        );

        String prompt = assembler.buildAgentExecutionPrompt(instance, "代码开发", config, Map.of("triggered", true));

        assertThat(prompt).contains("目标产物: docs/AIP-101-implementation.md");
        assertThat(prompt).contains("代码开发阶段如发现上游文档给出的目录或文件路径不存在");
        assertThat(prompt).contains("至少完成一次真实编辑动作");
        assertThat(prompt).contains("mvn test、mvn -pl <module> test、mvn compile、pnpm test、pnpm build");
        assertThat(prompt).contains("代码开发结果");
        assertThat(prompt).contains("已修改 PolicyController.java 并补充 WorkflowController.java");
    }

    @Test
    void shouldResolveTestPlanArtifactPathForTestPlanningNode() {
        WorkflowInstance instance = buildInstance();
        Map<String, Object> config = Map.of(
                "instructionSource", "manual",
                "taskInstruction", "请输出测试规划文档",
                "artifactDocType", "test_plan",
                "outputVariableKey", "testPlanDoc"
        );

        String prompt = assembler.buildAgentExecutionPrompt(instance, "测试规划", config, Map.of("triggered", true));

        assertThat(prompt).contains("目标产物: docs/AIP-101-test-plan.md");
        assertThat(prompt).contains("测试规划必须明确引用代码开发阶段的真实改动文件");
    }

    private WorkflowInstance buildInstance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setSpecId("spec-1");
        instance.setName("标准研发工作流");
        Map<String, Object> variables = new HashMap<>();
        variables.put("workflowGoal", "输出完整的技术设计与测试规划文档");
        variables.put("specName", "工作流引擎缺陷修复");
        variables.put("jiraTicket", "AIP-101");
        variables.put("artifactOutputPath", "docs/AIP-101-technical-design.md");
        variables.put("workspaceName", "SchemaPlexAI 主工程");
        variables.put("workspacePath", "/tmp/workspace");
        instance.setVariables(variables);
        return instance;
    }
}
