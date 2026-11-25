package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.ApiGatewayMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.desktop.DesktopExecutionTarget;
import com.schemaplexai.service.desktop.DesktopToolBroker;
import com.schemaplexai.service.ai.LangChain4jToolSpecProvider;
import com.schemaplexai.common.enums.SandboxProfileEnum;
import com.schemaplexai.common.enums.ToolIoTypeEnum;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import com.schemaplexai.service.agent.tool.executor.BuiltinToolExecutor;
import com.schemaplexai.service.agent.tool.executor.SkillToolExecutor;
import com.schemaplexai.service.agent.tool.executor.ApiGatewayToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.integration.mcp.LangChain4jMcpClientFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolServiceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.springframework.beans.factory.ObjectProvider;

class AgentToolSessionFactoryTest {

    @Test
    void keepsToolSearchWhenAnAuthorizedToolRemainsDiscoverable() {
        var builtin = ToolSpecification.builder().name("sys_read")
                .addMetadata(ToolSpecification.METADATA_SEARCH_BEHAVIOR, SearchBehavior.ALWAYS_VISIBLE).build();
        var discoverable = ToolSpecification.builder().name("inventory_query").description("读取库存")
                .addMetadata(ToolSpecification.METADATA_SEARCH_BEHAVIOR, SearchBehavior.SEARCHABLE).build();
        var tools = List.of(builtin, discoverable);
        var context = ToolServiceContext.builder().availableTools(tools).toolSpecifications(tools)
                .effectiveTools(tools).toolExecutors(Map.of()).build();

        var round = new LangChainToolSearchAdapter().adjust(context, null, null);

        assertThat(round.effectiveTools()).extracting(ToolSpecification::name)
                .containsExactly("sys_read", "tool_search_tool");
        assertThat(round.toolExecutors()).containsKey("tool_search_tool");
    }

    @Test
    void shouldAdvertiseDesktopToolParametersAndDispatchToSelectedWorkspace() {
        var objectMapper = new ObjectMapper();
        var builtin = mock(BuiltinToolExecutor.class);
        var broker = mock(DesktopToolBroker.class);
        var sandbox = mock(SandboxGuard.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> guardProvider = mock(ObjectProvider.class);
        var risk = mock(SecurityRuntimeGuardService.class);
        when(guardProvider.getObject()).thenReturn(risk);
        var factory = new AgentToolSessionFactory(objectMapper, mock(AgentToolBindingMapper.class),
                mock(BuiltinToolMapper.class), mock(SkillMapper.class), mock(McpServerMapper.class),
                mock(ApiGatewayMapper.class), builtin, mock(SkillToolExecutor.class), mock(ApiGatewayToolExecutor.class),
                new LangChain4jToolSpecProvider(), mock(LangChain4jMcpClientFactory.class), sandbox, guardProvider, broker);
        var target = new DesktopExecutionTarget("device-1", "local");
        var context = AgentExecutionContext.builder().tenantId("tenant-1").agentId("agent-1").userId("user-1")
                .parentExecutionId("team-1").executionId("member-1")
                .inputContext(Map.of(DesktopExecutionTarget.DEVICE_CONTEXT_KEY, target.deviceId(),
                        DesktopExecutionTarget.WORKSPACE_CONTEXT_KEY, target.workspaceId()))
                .desktopVerificationCommand("mvn clean package")
                .overrideToolBindings(List.of(builtinBinding("sys.write"), builtinBinding("sys.bash"),
                        builtinBinding("sys.glob"), builtinBinding("sys.rm")))
                .build();
        when(broker.execute(same(context), eq(target), any(ToolCall.class)))
                .thenReturn(ToolResult.builder().success(true).result(objectMapper.valueToTree("已保存")).build());

        try (var session = factory.openSession(context, "conv-1")) {
            assertThat(session.augmentSystemPrompt("角色规则", 3))
                    .contains("角色规则", "同轮读取相互独立的文件", "复用同一文件已成功读取且未截断的内容",
                            "先单独创建目录，拿到成功回执后再提交依赖该目录的写入",
                            "文件已变化或现有证据不足时，再读取相关文件", "完整 content", "优先用测试或构建实证",
                            "交付中只列相对于授权工作区的路径", "Git 根验证报告是否与授权目录一致",
                            "测试数以最终构建回执的汇总为准", "再完成最终构建，分别说明两者",
                            "独立核对退出码的调用，避免命令串联、管道和重定向",
                            "每轮最多可提交 3 笔工具调用", "同一产物上互不依赖的 CLI 检查成组提交",
                            "逐项覆盖工单要求的正常和异常输入", "该用例已通过", "同一产物未变化且回执已证明契约时，复用该回执",
                            "Picked up JDK_JAVA_OPTIONS: 行属于启动器诊断", "保留沙箱工具链环境",
                            "收到并核对全部前序回执", "在接下来的一轮只用 sys_bash", "workingDirectory 为 .",
                            "命令必须原样提交", "mvn clean package")
                    .doesNotContain("最终构建前复用已有读取证据");
            context.setParentExecutionId(null);
            assertThat(session.augmentSystemPrompt("独立开发规则", 8))
                    .contains("每轮最多可提交 8 笔工具调用", "同一产物上互不依赖的 CLI 检查成组提交",
                            "文件已核对且随后未被修改时",
                            "文件可能有变化或证据不足时，重新读取所需文件",
                            "收到并核对全部前序回执", "在接下来的一轮只用 sys_bash", "mvn clean package");
            context.setParentExecutionId("team-1");
            context.setDesktopVerificationDelegated(true);
            assertThat(session.augmentSystemPrompt("角色规则", 3))
                    .contains("独立核对退出码", "每轮最多可提交 3 笔工具调用")
                    .doesNotContain("收到并核对全部前序回执", "mvn clean package");
            assertThat(context.getDesktopVerificationCommand()).isEqualTo("mvn clean package");
            context.setDesktopVerificationDelegated(false);
            var tools = session.getBaseContext();
            var round = session.buildRoundContext(null, 1);
            assertThat(round.isDesktopTool("sys_write")).isTrue();
            assertThat(round.isDesktopTool("sys_rm")).isFalse();
            assertThat(round.ioTypeIndex()).doesNotContainKey("tool_search_tool");
            assertThat(round.toolServiceContext().effectiveTools()).isEqualTo(tools.effectiveTools());
            assertThat(round.ioTypeIndex().get("sys_write").isWrite()).isTrue();
            var cloudExecutor = new GuardedToolExecutor(mock(dev.langchain4j.service.tool.ToolExecutor.class),
                    objectMapper, "tenant-1", "agent-1", null, "mcp", null, sandbox, risk);
            var cloudRound = new AgentToolSessionFactory.RoundToolContext(
                    tools.toBuilder().toolExecutors(Map.of("sys_write", cloudExecutor)).build(),
                    round.invocationContext(), round.ioTypeIndex());
            assertThat(cloudRound.isDesktopTool("sys_write")).isFalse();
            assertThat(tools.effectiveTools()).extracting(tool -> tool.name())
                    .containsExactly("sys_write", "sys_bash", "sys_glob");
            var write = (JsonObjectSchema) tools.effectiveTools().get(0).parameters();
            assertThat(write.required()).containsExactly("path", "content");
            assertThat(write.properties()).containsKeys("path", "content", "workingDirectory");
            var bash = (JsonObjectSchema) tools.effectiveTools().get(1).parameters();
            assertThat(bash.required()).containsExactly("command");
            assertThat(bash.properties()).containsKeys("command", "workingDirectory");
            var glob = (JsonObjectSchema) tools.effectiveTools().get(2).parameters();
            assertThat(glob.required()).containsExactly("pattern");
            assertThat(glob.properties()).containsKeys("pattern", "path", "workingDirectory");
            var pattern = (JsonStringSchema) glob.properties().get("pattern");
            assertThat(pattern.description()).contains("只搜索当前这一层", "path 或 workingDirectory", "*.java");

            var request = ToolExecutionRequest.builder().id("write-1").name("sys_write")
                    .arguments("""
                            {"path":"src/Main.java","content":"class Main {}"}
                            """).build();
            var result = tools.toolExecutors().get("sys_write").executeWithContext(request, null);
            assertThat(result.resultText()).contains("已保存");
            verify(broker).validateTarget("tenant-1", "user-1", target);
            verify(broker).execute(same(context), eq(target), any(ToolCall.class));

            clearInvocations(broker);
            var pause = new SecurityCheckDecisionVO();
            pause.setDecision(SecurityComplianceConstant.DECISION_PAUSE);
            pause.setMessage("企业风险暂停");
            when(risk.evaluate(any(), eq(null))).thenAnswer(invocation -> {
                SecurityRuntimeCheckRequest check = invocation.getArgument(0);
                assertThat(check.getScene()).isEqualTo(SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE);
                assertThat(check.getResourceType()).isEqualTo(SecurityComplianceConstant.RESOURCE_TYPE_AGENT_EXECUTION);
                assertThat(check.getResourceId()).isEqualTo("member-1");
                return pause;
            });
            var paused = tools.toolExecutors().get("sys_write").executeWithContext(request, null);
            assertThat(((ToolResult) paused.result()).getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_PAUSE);
            verifyNoInteractions(broker);
        }

        context.setOverrideToolBindings(List.of(builtinBinding("sys.read")));
        try (var readOnly = factory.openSession(context, "conv-1")) {
            var schema = (JsonObjectSchema) readOnly.getBaseContext().availableTools().getFirst().parameters();
            assertThat(schema.properties()).doesNotContainKeys("startLine", "endLine");
            assertThat(readOnly.augmentSystemPrompt("角色规则", 3))
                    .contains("本机工作区执行边界")
                    .doesNotContain("同一轮模型响应中发出多条独立工具调用", "mvn clean package", "startLine");
        }
        when(broker.supportsReadRange("tenant-1", "user-1", target.deviceId())).thenReturn(true);
        try (var rangedRead = factory.openSession(context, "conv-1")) {
            var schema = (JsonObjectSchema) rangedRead.getBaseContext().availableTools().getFirst().parameters();
            assertThat(schema.required()).containsExactly("path");
            assertThat(schema.properties()).containsKeys("startLine", "endLine");
            assertThat(rangedRead.augmentSystemPrompt("角色规则", 3)).contains("startLine 和 endLine", "读取全文");
        }

        context.setOverrideToolBindings(List.of(builtinBinding("sys.edit")));
        try (var legacy = factory.openSession(context, "conv-1")) {
            var schema = (JsonObjectSchema) legacy.getBaseContext().availableTools().getFirst().parameters();
            assertThat(schema.required()).containsExactly("path", "content");
            assertThat(schema.properties()).doesNotContainKey("oldText");
        }
        when(broker.supportsExactEdit("tenant-1", "user-1", target.deviceId())).thenReturn(true);
        try (var capable = factory.openSession(context, "conv-1")) {
            var schema = (JsonObjectSchema) capable.getBaseContext().availableTools().getFirst().parameters();
            assertThat(schema.required()).containsExactly("path");
            assertThat(schema.properties()).containsKeys("content", "oldText", "newText");
            assertThat(capable.augmentSystemPrompt("角色规则", 3)).contains("唯一 oldText 和 newText");
        }

        context.setInputContext(Map.of());
        try (var cloud = factory.openSession(context, "conv-1")) {
            assertThat(cloud.augmentSystemPrompt("角色规则", 3)).isEqualTo("角色规则");
        }
    }

    private AgentToolBinding builtinBinding(String code) {
        var binding = new AgentToolBinding();
        binding.setToolCode(code);
        binding.setSourceType("builtin");
        binding.setEnabled(true);
        return binding;
    }

    @Test
    void shouldTrackActivatedSkillNameAndExposeLazyLoadedSkillTool() {
        ObjectMapper objectMapper = new ObjectMapper();
        AgentToolBinding binding = buildSkillBinding();
        Skill skill = buildLazyLoadedSkill();

        AgentToolBindingMapper bindingMapper = mock(AgentToolBindingMapper.class);
        BuiltinToolMapper builtinToolMapper = mock(BuiltinToolMapper.class);
        SkillMapper skillMapper = mock(SkillMapper.class);
        McpServerMapper mcpServerMapper = mock(McpServerMapper.class);
        ApiGatewayMapper apiGatewayMapper = mock(ApiGatewayMapper.class);
        BuiltinToolExecutor builtinToolExecutor = mock(BuiltinToolExecutor.class);
        SkillToolExecutor skillToolExecutor = mock(SkillToolExecutor.class);
        ApiGatewayToolExecutor apiGatewayToolExecutor = mock(ApiGatewayToolExecutor.class);
        LangChain4jMcpClientFactory mcpClientFactory = mock(LangChain4jMcpClientFactory.class);
        SandboxGuard sandboxGuard = mock(SandboxGuard.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SecurityRuntimeGuardService> securityRuntimeGuardServiceProvider = mock(ObjectProvider.class);
        SandboxPolicy sandboxPolicy = SandboxPolicy.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .sandboxProfile(SandboxProfileEnum.STANDARD)
                .networkEgressEnabled(true)
                .build();

        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(skillMapper.selectOne(any())).thenReturn(skill);
        when(securityRuntimeGuardServiceProvider.getObject()).thenReturn(null);
        when(sandboxGuard.validateTool(any(SandboxPolicy.class), anyString(), any(ToolCall.class))).thenReturn(null);
        when(skillToolExecutor.execute(eq("tenant-1"), eq("agent-1"), same(binding), any(ToolCall.class), same(sandboxPolicy)))
                .thenReturn(ToolResult.builder()
                        .callId("call-skill")
                        .toolCode("skill.excel")
                        .success(true)
                        .result(objectMapper.valueToTree(Map.of("status", "ok")))
                        .build());

        AgentToolSessionFactory factory = new AgentToolSessionFactory(
                objectMapper,
                bindingMapper,
                builtinToolMapper,
                skillMapper,
                mcpServerMapper,
                apiGatewayMapper,
                builtinToolExecutor,
                skillToolExecutor,
                apiGatewayToolExecutor,
                new LangChain4jToolSpecProvider(),
                mcpClientFactory,
                sandboxGuard,
                securityRuntimeGuardServiceProvider
        );

        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
                .sandboxPolicy(sandboxPolicy)
                .build();

        try (AgentToolSessionFactory.AgentToolSession session = factory.openSession(context, "conv-1")) {
            assertThat(session.getBaseContext().effectiveTools())
                    .extracting(dev.langchain4j.agent.tool.ToolSpecification::name)
                    .contains("activate_skill")
                    .doesNotContain("skill.excel");

            ToolExecutionRequest activateRequest = ToolExecutionRequest.builder()
                    .id("call-activate")
                    .name("activate_skill")
                    .arguments("{\"skill_name\":\"skill.excel\"}")
                    .build();

            ToolExecutionResult activateResult = session.getBaseContext().toolExecutors().get("activate_skill")
                    .executeWithContext(activateRequest, null);
            assertThat(activateResult).isNotNull();
            assertThat(activateResult.attributes()).containsEntry("activatedSkillName", "skill.excel");

            ChatMemory chatMemory = mock(ChatMemory.class);
            when(chatMemory.messages()).thenReturn(List.of(
                    UserMessage.from("请生成 Excel"),
                    ToolExecutionResultMessage.builder()
                            .id("call-activate")
                            .toolName("activate_skill")
                            .text(activateResult.resultText())
                            .attributes(activateResult.attributes())
                            .build()
            ));

            AgentToolSessionFactory.RoundToolContext roundToolContext = session.buildRoundContext(chatMemory, 1);
            assertThat(roundToolContext.toolServiceContext().effectiveTools())
                    .extracting(dev.langchain4j.agent.tool.ToolSpecification::name)
                    .contains("skill_excel");

            ToolExecutionRequest skillRequest = ToolExecutionRequest.builder()
                    .id("call-skill")
                    .name("skill_excel")
                    .arguments("{\"fileName\":\"report.xlsx\",\"data\":[{\"name\":\"demo\"}]}")
                    .build();
            ToolExecutionResult skillResult = roundToolContext.toolServiceContext().toolExecutors().get("skill_excel")
                    .executeWithContext(skillRequest, roundToolContext.invocationContext());

            assertThat(skillResult).isNotNull();
            assertThat(skillResult.isError()).isFalse();
            assertThat(skillResult.result()).isInstanceOf(ToolResult.class);
            assertThat(((ToolResult) skillResult.result()).isSuccess()).isTrue();
        }
    }

    private AgentToolBinding buildSkillBinding() {
        AgentToolBinding binding = new AgentToolBinding();
        binding.setId("binding-1");
        binding.setTenantId("tenant-1");
        binding.setAgentId("agent-1");
        binding.setToolCode("skill.excel");
        binding.setSourceType("skill");
        binding.setSourceRefId("skill-1");
        binding.setEnabled(true);
        binding.setPriority(1);
        return binding;
    }

    private Skill buildLazyLoadedSkill() {
        Skill skill = new Skill();
        skill.setId("skill-1");
        skill.setTenantId("tenant-1");
        skill.setName("skill.excel");
        skill.setDisplayName("Excel 生成器");
        skill.setDescription("将结构化数据输出为 Excel");
        skill.setSkillPrompt("用于生成 Excel 报表");
        skill.setParameters(List.of(
                Map.of("name", "fileName", "type", "string", "required", true),
                Map.of("name", "data", "type", "array", "required", true)
        ));
        skill.setExposureConfig(Map.of(
                "searchBehavior", "searchable",
                "progressiveDisclosure", true,
                "lazyLoad", true
        ));
        skill.setImplementation(Map.of(
                "type", "builtin",
                "class", "com.schemaplexai.service.agent.tool.builtin.ExcelSkillExecutor"
        ));
        skill.setStatus("active");
        return skill;
    }
}
