package com.schemaplexai.service.desktop;

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
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.execution.SandboxPolicy;
import com.schemaplexai.service.agent.tool.executor.ApiGatewayToolExecutor;
import com.schemaplexai.service.agent.tool.executor.BuiltinToolExecutor;
import com.schemaplexai.service.agent.tool.executor.SkillToolExecutor;
import com.schemaplexai.service.agent.tool.langchain4j.AgentToolSessionFactory;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import com.schemaplexai.service.ai.LangChain4jToolSpecProvider;
import com.schemaplexai.service.integration.mcp.LangChain4jMcpClientFactory;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DesktopToolRoutingTest {

    @Test
    void shouldRouteActualLangChainBindingToDesktopAndPreservePrePostSecurity() {
        Fixture fixture = new Fixture("sys.read", true);
        when(fixture.broker.execute(eq(fixture.context), eq(new DesktopExecutionTarget("device", "workspace")), any()))
                .thenReturn(fixture.result());

        ToolExecutionResult result = fixture.execute("{\"path\":\"README.md\"}");

        assertThat(result.isError()).isFalse();
        assertThat(((ToolResult) result.result()).getResult().asText()).isEqualTo("local content");
        ArgumentCaptor<ToolCall> call = ArgumentCaptor.forClass(ToolCall.class);
        verify(fixture.broker).execute(eq(fixture.context), eq(new DesktopExecutionTarget("device", "workspace")), call.capture());
        assertThat(call.getValue().getCallId()).isEqualTo("model-call");
        assertThat(call.getValue().getToolCode()).isEqualTo("sys.read");
        assertThat(call.getValue().getArguments().path("path").asText()).isEqualTo("README.md");
        verifyNoInteractions(fixture.builtin);
        ArgumentCaptor<SecurityRuntimeCheckRequest> checks = ArgumentCaptor.forClass(SecurityRuntimeCheckRequest.class);
        verify(fixture.security, times(2)).evaluate(checks.capture(), isNull());
        assertThat(checks.getAllValues()).extracting(SecurityRuntimeCheckRequest::getScene)
                .containsExactly(SecurityComplianceConstant.CHECK_SCENE_TOOL_PRE, SecurityComplianceConstant.CHECK_SCENE_TOOL_POST);
    }

    @Test
    void shouldRetainCloudExecutorWithoutExplicitDesktopTarget() {
        Fixture fixture = new Fixture("sys.read", false);
        when(fixture.builtin.execute(eq("tenant"), eq("agent"), eq(fixture.binding), any(), eq(fixture.policy)))
                .thenReturn(fixture.result());
        assertThat(fixture.execute("{\"path\":\"README.md\"}").isError()).isFalse();
        verifyNoInteractions(fixture.broker);
        verify(fixture.builtin).execute(eq("tenant"), eq("agent"), eq(fixture.binding), any(), eq(fixture.policy));
    }

    @Test
    void shouldKeepCloudCapabilitiesOnServerEvenWhenDesktopIsConnected() {
        Fixture fixture = new Fixture("web.fetch", true);
        when(fixture.builtin.execute(eq("tenant"), eq("agent"), eq(fixture.binding), any(), eq(fixture.policy)))
                .thenReturn(fixture.result());
        assertThat(fixture.execute("{\"url\":\"https://example.com\"}").isError()).isFalse();
        verify(fixture.broker, never()).execute(any(), any(), any());
        verify(fixture.builtin).execute(eq("tenant"), eq("agent"), eq(fixture.binding), any(), eq(fixture.policy));
    }

    @Test
    void shouldBlockDesktopDispatchWhenRuntimePolicyRequiresApproval() {
        Fixture fixture = new Fixture("sys.write", true);
        SecurityCheckDecisionVO decision = new SecurityCheckDecisionVO();
        decision.setDecision(SecurityComplianceConstant.DECISION_PAUSE);
        decision.setMessage("需要安全审批");
        when(fixture.security.evaluate(any(SecurityRuntimeCheckRequest.class), isNull())).thenReturn(decision);
        ToolExecutionResult result = fixture.execute("{\"path\":\"a.txt\",\"content\":\"new\"}");
        assertThat(result.isError()).isTrue();
        assertThat(((ToolResult) result.result()).getControlAction()).isEqualTo(SecurityComplianceConstant.DECISION_PAUSE);
        verify(fixture.broker, never()).execute(any(), any(), any());
        verifyNoInteractions(fixture.builtin);
    }

    @Test
    void shouldApplyEnterpriseCommandPolicyBeforeDispatchingLocalBash() {
        Fixture fixture = new Fixture("sys.bash", true);
        when(fixture.guard.checkCommand("sudo rm -rf /", fixture.policy)).thenReturn("禁止提权命令");
        ToolExecutionResult result = fixture.execute("{\"command\":\"sudo rm -rf /\"}");
        assertThat(result.isError()).isTrue();
        verify(fixture.broker, never()).execute(any(), any(), any());
        verifyNoInteractions(fixture.builtin);
    }

    @Test
    void shouldNeverFallbackLocalMutationToServer() {
        Fixture fixture = new Fixture("sys.write", true);
        when(fixture.broker.execute(any(), any(), any())).thenReturn(ToolResult.builder()
                .callId("model-call").toolCode("sys.write").success(false).errorMessage("设备离线").build());
        assertThat(fixture.execute("{\"path\":\"a.txt\",\"content\":\"fixture\"}").isError()).isTrue();
        verify(fixture.broker).execute(any(), any(), any());
        verifyNoInteractions(fixture.builtin);
    }

    @Test
    void shouldRejectServerOrEscapingPathsBeforeDispatch() {
        Fixture fixture = new Fixture("sys.read", true);
        assertThat(fixture.execute("{\"path\":\"/server/workspace/README.md\"}").isError()).isTrue();
        assertThat(fixture.execute("{\"path\":\"../README.md\"}").isError()).isTrue();
        assertThat(fixture.execute("{\"path\":\"README.md\",\"workdir\":\"C:/server\"}").isError()).isTrue();
        verify(fixture.broker, never()).execute(any(), any(), any());
        verifyNoInteractions(fixture.builtin);
    }

    @Test
    void shouldTellModelToUseOnlyRelativeLocalWorkspacePaths() {
        Fixture fixture = new Fixture("sys.read", true);
        try (AgentToolSessionFactory.AgentToolSession session = fixture.factory.openSession(fixture.context, "conversation")) {
            assertThat(session.augmentSystemPrompt("企业共识规则", 3)).contains("企业共识规则", "本机工作区执行边界",
                    "不是真实目录", "工作区相对路径", "不得改在云端执行");
        }
    }

    private static final class Fixture {
        private final ObjectMapper mapper = new ObjectMapper();
        private final DesktopToolBroker broker = mock(DesktopToolBroker.class);
        private final BuiltinToolExecutor builtin = mock(BuiltinToolExecutor.class);
        private final SandboxGuard guard = mock(SandboxGuard.class);
        private final SecurityRuntimeGuardService security = mock(SecurityRuntimeGuardService.class);
        private final AgentToolBinding binding = new AgentToolBinding();
        private final SandboxPolicy policy = SandboxPolicy.builder().executionId("execution").build();
        private final AgentExecutionContext context;
        private final AgentToolSessionFactory factory;

        private Fixture(String toolCode, boolean desktop) {
            binding.setAgentId("agent");
            binding.setTenantId("tenant");
            binding.setToolCode(toolCode);
            binding.setSourceType("builtin");
            binding.setEnabled(true);
            binding.setPriority(1);
            context = AgentExecutionContext.builder().tenantId("tenant").userId("user").agentId("agent")
                    .executionId("execution").sandboxPolicy(policy).overrideToolBindings(List.of(binding))
                    .inputContext(desktop ? Map.of("desktopDeviceId", "device", "desktopWorkspaceId", "workspace") : null).build();
            BuiltinToolMapper toolMapper = mock(BuiltinToolMapper.class);
            BuiltinTool tool = new BuiltinTool();
            tool.setCode(toolCode);
            tool.setDescription("本机工具");
            tool.setInputSchema("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}");
            when(toolMapper.selectOne(any())).thenReturn(tool);
            @SuppressWarnings("unchecked")
            ObjectProvider<SecurityRuntimeGuardService> securityProvider = mock(ObjectProvider.class);
            when(securityProvider.getObject()).thenReturn(security);
            when(guard.validateTool(any(SandboxPolicy.class), anyString(), any(ToolCall.class))).thenReturn(null);
            factory = new AgentToolSessionFactory(mapper, mock(AgentToolBindingMapper.class), toolMapper,
                    mock(SkillMapper.class), mock(McpServerMapper.class), mock(ApiGatewayMapper.class), builtin,
                    mock(SkillToolExecutor.class), mock(ApiGatewayToolExecutor.class), new LangChain4jToolSpecProvider(),
                    mock(LangChain4jMcpClientFactory.class), guard, securityProvider, broker);
        }

        private ToolExecutionResult execute(String arguments) {
            try (AgentToolSessionFactory.AgentToolSession session = factory.openSession(context, "conversation")) {
                String toolName = session.getBaseContext().effectiveTools().getFirst().name();
                return session.getBaseContext().toolExecutors().get(toolName).executeWithContext(
                        ToolExecutionRequest.builder().id("model-call").name(toolName).arguments(arguments).build(), null);
            }
        }

        private ToolResult result() {
            return ToolResult.builder().callId("model-call").toolCode(binding.getToolCode()).success(true)
                    .result(mapper.valueToTree("local content")).build();
        }
    }
}
