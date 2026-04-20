package com.schemaplexai.service.agent.tool.langchain4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.AgentToolBindingMapper;
import com.schemaplexai.dao.mapper.ApiGatewayMapper;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.dao.mapper.McpServerMapper;
import com.schemaplexai.dao.mapper.SkillMapper;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.entity.Skill;
import com.schemaplexai.service.ai.LangChain4jToolSpecProvider;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.tool.executor.BuiltinToolExecutor;
import com.schemaplexai.service.agent.tool.executor.SkillToolExecutor;
import com.schemaplexai.service.agent.tool.executor.ApiGatewayToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.integration.mcp.LangChain4jMcpClientFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.when;

class AgentToolSessionFactoryTest {

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

        when(bindingMapper.selectList(any())).thenReturn(List.of(binding));
        when(skillMapper.selectOne(any())).thenReturn(skill);
        when(skillToolExecutor.execute(eq("tenant-1"), eq("agent-1"), same(binding), any(ToolCall.class), isNull()))
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
                mcpClientFactory
        );

        AgentExecutionContext context = AgentExecutionContext.builder()
                .tenantId("tenant-1")
                .agentId("agent-1")
                .executionId("exec-1")
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
                    .contains("skill.excel");

            ToolExecutionRequest skillRequest = ToolExecutionRequest.builder()
                    .id("call-skill")
                    .name("skill.excel")
                    .arguments("{\"fileName\":\"report.xlsx\",\"data\":[{\"name\":\"demo\"}]}")
                    .build();
            ToolExecutionResult skillResult = roundToolContext.toolServiceContext().toolExecutors().get("skill.excel")
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
