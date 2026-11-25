package com.schemaplexai.service.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.dao.mapper.AgentExecutionLogMapper;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.dto.desktop.DesktopToolResultRequest;
import com.schemaplexai.model.entity.AgentExecutionLog;
import com.schemaplexai.model.entity.AgentToolBinding;
import com.schemaplexai.model.vo.desktop.DesktopToolCallVO;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.AgentTerminalEventOutbox;
import com.schemaplexai.service.agent.execution.AgentLoopToolHandler;
import com.schemaplexai.service.agent.execution.SandboxGuard;
import com.schemaplexai.service.agent.tool.langchain4j.LangChain4jBindingToolExecutor;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesktopToolBrokerTest {

    @Test
    void shouldExposeExactEditOnlyForTheRegisteredDeviceOwner() {
        var broker = new DesktopToolBroker(new ObjectMapper());
        var capable = broker.register("tenant", "user", true).deviceId();
        var legacy = broker.register("tenant", "user").deviceId();

        assertThat(broker.supportsExactEdit("tenant", "user", capable)).isTrue();
        assertThat(broker.supportsExactEdit("tenant", "user", legacy)).isFalse();
        assertThat(broker.supportsExactEdit("tenant", "other", capable)).isFalse();
        assertThat(broker.supportsExactEdit("other", "user", capable)).isFalse();
        broker.unregister("tenant", "user", capable);
        assertThat(broker.supportsExactEdit("tenant", "user", capable)).isFalse();
    }

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldRequireTheOwnersReadRangeCapabilityAndRejectInvalidLinesBeforeDispatch() throws Exception {
        var broker = new DesktopToolBroker(mapper);
        var capable = broker.register("tenant", "user", false, true).deviceId();
        var legacy = broker.register("tenant", "user").deviceId();
        assertThat(broker.supportsReadRange("tenant", "user", capable)).isTrue();
        assertThat(broker.supportsReadRange("tenant", "other", capable)).isFalse();
        assertThat(broker.supportsReadRange("other", "user", capable)).isFalse();
        assertThat(broker.supportsReadRange("tenant", "user", legacy)).isFalse();

        var ranged = ToolCall.builder().callId("read-range").toolCode("sys.read")
                .arguments(mapper.createObjectNode().put("path", "Main.java").put("startLine", 3).put("endLine", 4)).build();
        var oldResult = broker.execute(context(), new DesktopExecutionTarget(legacy, "ws"), ranged);
        assertThat(oldResult.getErrorMessage()).contains("仅支持读取完整文件");
        assertThat(broker.takePending("tenant", "user", legacy)).isEmpty();

        for (String lines : List.of("\"startLine\":0", "\"startLine\":1.5", "\"endLine\":null",
                "\"endLine\":\"4\"", "\"startLine\":5,\"endLine\":4")) {
            var invalid = ToolCall.builder().callId("read-invalid").toolCode("sys.read")
                    .arguments(mapper.readTree("{\"path\":\"Main.java\"," + lines + "}")).build();
            assertThat(broker.execute(context(), new DesktopExecutionTarget(capable, "ws"), invalid).getErrorMessage())
                    .contains("文件读取行号必须为正整数");
        }
        assertThat(broker.takePending("tenant", "user", capable)).isEmpty();

        var future = CompletableFuture.supplyAsync(() ->
                broker.execute(context(), new DesktopExecutionTarget(capable, "ws"), ranged));
        var request = awaitCall(broker, capable);
        assertThat(request.arguments().path("startLine").intValue()).isEqualTo(3);
        assertThat(request.arguments().path("endLine").intValue()).isEqualTo(4);
        broker.complete("tenant", "user", capable, request.callId(),
                new DesktopToolResultRequest(true, mapper.getNodeFactory().textNode("[文件片段]"), null));
        assertThat(future.get(2, TimeUnit.SECONDS).isSuccess()).isTrue();
    }

    @Test
    void shouldRequireExactRootCommandAndInvalidateEvidenceAfterAnotherWorkspaceOperation() throws Exception {
        AgentExecutionLogMapper logMapper = mock(AgentExecutionLogMapper.class);
        List<AgentExecutionLog> recorded = new CopyOnWriteArrayList<>();
        when(logMapper.insert(any(AgentExecutionLog.class))).thenAnswer(invocation -> {
            recorded.add(invocation.getArgument(0));
            return 1;
        });
        when(logMapper.selectList(any())).thenAnswer(invocation -> List.copyOf(recorded));
        AgentLogService logs = new AgentLogService(logMapper, mock(AgentExecutionMapper.class),
                mock(ApplicationEventPublisher.class), mock(AgentTerminalEventOutbox.class));
        DesktopToolBroker broker = new DesktopToolBroker(mapper, logs);
        String device = broker.register("tenant", "user").deviceId();
        AgentExecutionContext child = context();
        child.setParentExecutionId("parent");
        child.setDesktopVerificationCommand("mvn clean package");
        AgentExecutionContext parent = context();
        parent.setExecutionId("parent");
        var success = new DesktopToolResultRequest(true,
                mapper.getNodeFactory().textNode("{\"exitCode\":0,\"stdout\":\"BUILD SUCCESS\"}"), null);
        var misleading = new DesktopToolResultRequest(true,
                mapper.getNodeFactory().textNode("{\"exitCode\":1,\"stdout\":\"BUILD SUCCESS\"}"), null);
        var command = mapper.createObjectNode().put("command", "mvn clean package");
        var otherCommand = mapper.createObjectNode().put("command", "git status");

        completeCall(broker, child, device, "sys.bash", command.deepCopy().put("workingDirectory", "nested"), success);
        completeCall(broker, child, device, "sys.bash", otherCommand, success);
        completeCall(broker, child, device, "sys.bash", command, misleading);
        assertThat(logs.hasCurrentDesktopVerification(parent)).isFalse();

        completeCall(broker, child, device, "sys.bash", command, success);
        completeCall(broker, child, device, "sys.read", mapper.createObjectNode().put("path", "pom.xml"), success);
        assertThat(logs.hasCurrentDesktopVerification(parent)).isTrue();
        assertThat(recorded).allSatisfy(entry -> assertThat(entry.getExecutionId()).isEqualTo("parent"));

        completeCall(broker, child, device, "sys.write", mapper.createObjectNode().put("path", "pom.xml"), success);
        assertThat(logs.hasCurrentDesktopVerification(parent)).isFalse();
        completeCall(broker, child, device, "sys.bash", command, success);
        assertThat(logs.hasCurrentDesktopVerification(parent)).isTrue();
    }

    private void completeCall(DesktopToolBroker broker, AgentExecutionContext context, String device, String toolCode,
                              JsonNode args, DesktopToolResultRequest response) throws Exception {
        ToolCall tool = ToolCall.builder().callId("model-call").toolCode(toolCode).arguments(args).build();
        CompletableFuture<ToolResult> execution = CompletableFuture.supplyAsync(() ->
                broker.execute(context, new DesktopExecutionTarget(device, "workspace"), tool));
        DesktopToolCallVO request = awaitCall(broker, device);
        broker.complete("tenant", "user", device, request.callId(), response);
        execution.get(2, TimeUnit.SECONDS);
    }

    @Test
    void shouldRoundTripOriginalToolIdentityAndDeliverExactlyOnce() throws Exception {
        DesktopToolBroker broker = new DesktopToolBroker(mapper);
        String deviceId = broker.register("tenant", "user").deviceId();
        CompletableFuture<ToolResult> execution = submit(broker, deviceId);
        DesktopToolCallVO call = awaitCall(broker, deviceId);

        assertThat(call.executionId()).isEqualTo("execution");
        assertThat(call.parentExecutionId()).isNull();
        assertThat(call.agentId()).isEqualTo("agent");
        assertThat(call.workspaceId()).isEqualTo("workspace");
        assertThat(call.toolCode()).isEqualTo("sys.read");
        assertThat(call.arguments().path("path").asText()).isEqualTo("README.md");
        assertThat(call.callId()).isNotEqualTo("model-call");
        assertThat(broker.takePending("tenant", "user", deviceId)).isEmpty();
        broker.validateCall("tenant", "user", deviceId, call.callId());

        broker.complete("tenant", "user", deviceId, call.callId(),
                new DesktopToolResultRequest(true, mapper.valueToTree("local file content"), null));
        ToolResult result = execution.get(2, TimeUnit.SECONDS);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getCallId()).isEqualTo("model-call");
        assertThat(result.getDesktopCallId()).isEqualTo(UUID.fromString(call.callId()));
        assertThat(result.getToolCode()).isEqualTo("sys.read");
        assertThat(result.getResult().asText()).isEqualTo("local file content");
        assertThatThrownBy(() -> broker.validateCall("tenant", "user", deviceId, call.callId()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> broker.complete("tenant", "user", deviceId, call.callId(),
                new DesktopToolResultRequest(true, mapper.valueToTree("replay"), null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void shouldArchiveOnlyTheBrokerGeneratedCorrelationIdForAnAcceptedFailureReceipt() throws Exception {
        var broker = new DesktopToolBroker(mapper);
        var deviceId = broker.register("tenant", "user").deviceId();
        var target = new DesktopExecutionTarget(deviceId, "workspace");
        var delegate = new DesktopToolExecutor(broker, context(), target, mock(SandboxGuard.class));
        var binding = new AgentToolBinding();
        binding.setToolCode("sys.read");
        var executor = new LangChain4jBindingToolExecutor(mapper, delegate, "tenant", "agent", binding, null);
        String callerId = UUID.randomUUID().toString();
        String outputId = UUID.randomUUID().toString();
        var request = ToolExecutionRequest.builder().id("provider-call").name("sys_read")
                .arguments(mapper.createObjectNode().put("path", "README.md").put("desktopCallId", callerId).toString())
                .build();
        var execution = CompletableFuture.supplyAsync(() -> executor.executeWithContext(request, null));
        var call = awaitCall(broker, deviceId);

        broker.complete("tenant", "user", deviceId, call.callId(),
                new DesktopToolResultRequest(false, mapper.createObjectNode().put("desktopCallId", outputId), "读取失败"));
        var result = execution.get(2, TimeUnit.SECONDS);
        var message = new AgentLoopToolHandler(mapper).buildResultMessage(request, (ToolResult) result.result(),
                result.attributes(), result.isError(), result.resultText());

        assertThat(message.id()).isEqualTo("provider-call");
        assertThat(message.isError()).isTrue();
        assertThat(message.attributes()).containsEntry("desktopCallId", call.callId());
        JsonNode archived = mapper.readTree(ChatMessageSerializer.messageToJson(message));
        assertThat(archived.path("attributes").path("desktopCallId").asText()).isEqualTo(call.callId());
        assertThat(result.resultText()).doesNotContain(call.callId());
        assertThat(mapper.readTree(result.resultText()).has("desktopCallId")).isFalse();
    }

    @Test
    void shouldDenyCrossTenantUserAndDeviceAccess() throws Exception {
        DesktopToolBroker broker = new DesktopToolBroker(mapper);
        String deviceId = broker.register("tenant", "user").deviceId();
        String otherDeviceId = broker.register("tenant", "user").deviceId();
        CompletableFuture<ToolResult> execution = submit(broker, deviceId);
        DesktopToolCallVO call = awaitCall(broker, deviceId);

        assertThatThrownBy(() -> broker.takePending("other-tenant", "user", deviceId))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> broker.takePending("tenant", "other-user", deviceId))
                .isInstanceOf(BusinessException.class);
        DesktopToolResultRequest result = new DesktopToolResultRequest(true, mapper.nullNode(), null);
        assertThatThrownBy(() -> broker.complete("tenant", "other-user", deviceId, call.callId(), result))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> broker.complete("tenant", "user", otherDeviceId, call.callId(), result))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> broker.unregister("tenant", "other-user", deviceId))
                .isInstanceOf(BusinessException.class);

        broker.complete("tenant", "user", deviceId, call.callId(), result);
        assertThat(execution.get(2, TimeUnit.SECONDS).isSuccess()).isTrue();
    }

    @Test
    void shouldFailSpoofedTargetWithoutPublishingAnyCall() {
        DesktopToolBroker broker = new DesktopToolBroker(mapper);
        String deviceId = broker.register("tenant", "victim").deviceId();
        ToolResult result = broker.execute(context(), new DesktopExecutionTarget(deviceId, "workspace"), tool());
        assertThat(result.isSuccess()).isFalse();
        assertThat(broker.takePending("tenant", "victim", deviceId)).isEmpty();
    }

    @Test
    void shouldRejectExpiredResultAndWakeWaiterOnDisconnect() throws Exception {
        AdjustableClock clock = new AdjustableClock();
        DesktopToolBroker broker = new DesktopToolBroker(mapper, clock, Duration.ofSeconds(120));
        String deviceId = broker.register("tenant", "user").deviceId();
        CompletableFuture<ToolResult> execution = submit(broker, deviceId);
        DesktopToolCallVO call = awaitCall(broker, deviceId);
        clock.advance(Duration.ofSeconds(121));
        assertThatThrownBy(() -> broker.validateCall("tenant", "user", deviceId, call.callId()))
                .isInstanceOf(BusinessException.class);

        assertThatThrownBy(() -> broker.complete("tenant", "user", deviceId, call.callId(),
                new DesktopToolResultRequest(true, mapper.nullNode(), null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("过期");
        broker.unregister("tenant", "user", deviceId);
        assertThat(execution.get(2, TimeUnit.SECONDS).isSuccess()).isFalse();
    }

    @Test
    void shouldTimeoutWithoutRepublishingWriteOrRetainingPendingCall() {
        DesktopToolBroker broker = new DesktopToolBroker(mapper, Clock.systemUTC(), Duration.ofMillis(10));
        String deviceId = broker.register("tenant", "user").deviceId();
        ToolCall write = ToolCall.builder().callId("write").toolCode("sys.write")
                .arguments(mapper.createObjectNode().put("path", "README.md").put("content", "new")).build();
        ToolResult result = broker.execute(context(), new DesktopExecutionTarget(deviceId, "workspace"), write);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getDesktopCallId()).isNull();
        assertThat(result.getErrorMessage()).contains("超时", "未自动重发");
        assertThat(broker.takePending("tenant", "user", deviceId)).isEmpty();
    }

    @Test
    void shouldExpireIdleDevicesAndBoundRegistrations() {
        AdjustableClock clock = new AdjustableClock();
        DesktopToolBroker broker = new DesktopToolBroker(mapper, clock, Duration.ofSeconds(120));
        String deviceId = broker.register("tenant", "user").deviceId();
        for (int index = 1; index < 8; index++) {
            broker.register("tenant", "user");
        }
        assertThatThrownBy(() -> broker.register("tenant", "user")).isInstanceOf(BusinessException.class);
        clock.advance(Duration.ofMinutes(6));
        assertThatThrownBy(() -> broker.takePending("tenant", "user", deviceId)).isInstanceOf(BusinessException.class);
        assertThat(broker.register("tenant", "user").deviceId()).isNotBlank();
    }

    @Test
    void shouldRejectOversizedResultWithoutCompletingCall() throws Exception {
        DesktopToolBroker broker = new DesktopToolBroker(mapper);
        String deviceId = broker.register("tenant", "user").deviceId();
        CompletableFuture<ToolResult> execution = submit(broker, deviceId);
        DesktopToolCallVO call = awaitCall(broker, deviceId);
        assertThatThrownBy(() -> broker.complete("tenant", "user", deviceId, call.callId(),
                new DesktopToolResultRequest(true, mapper.valueToTree("x".repeat(262145)), null)))
                .isInstanceOf(BusinessException.class);
        broker.unregister("tenant", "user", deviceId);
        assertThat(execution.get(2, TimeUnit.SECONDS).isSuccess()).isFalse();
    }

    @Test
    void shouldCancelPendingCallsAndBlockLaterDispatchFromSameExecution() throws Exception {
        DesktopToolBroker broker = new DesktopToolBroker(mapper);
        String deviceId = broker.register("tenant", "user").deviceId();
        CompletableFuture<ToolResult> execution = submit(broker, deviceId);
        DesktopToolCallVO call = awaitCall(broker, deviceId);
        broker.cancelExecution("tenant", "user", "execution");
        assertThatThrownBy(() -> broker.validateCall("tenant", "user", deviceId, call.callId()))
                .isInstanceOf(BusinessException.class);
        assertThat(execution.get(2, TimeUnit.SECONDS).getErrorMessage()).contains("取消");
        ToolResult later = broker.execute(context(), new DesktopExecutionTarget(deviceId, "workspace"), tool());
        assertThat(later.isSuccess()).isFalse();
        assertThat(later.getErrorMessage()).contains("取消");
        assertThat(broker.takePending("tenant", "user", deviceId)).isEmpty();
    }

    @Test
    void shouldExposeTeamParentAndCancelTheMemberWhenParentIsStopped() throws Exception {
        DesktopToolBroker broker = new DesktopToolBroker(mapper);
        String deviceId = broker.register("tenant", "user").deviceId();
        var context = context();
        context.setParentExecutionId("team-parent");
        var execution = CompletableFuture.supplyAsync(() -> broker.execute(context,
                new DesktopExecutionTarget(deviceId, "workspace"), tool()));
        DesktopToolCallVO call = awaitCall(broker, deviceId);

        assertThat(call.executionId()).isEqualTo("execution");
        assertThat(call.parentExecutionId()).isEqualTo("team-parent");
        broker.cancelExecution("tenant", "user", "team-parent");
        assertThat(execution.get(2, TimeUnit.SECONDS).getErrorMessage()).contains("取消");
    }

    @Test
    void shouldRejectAlreadyExpiredExecutionDeadlineWithoutDispatch() {
        DesktopToolBroker broker = new DesktopToolBroker(mapper);
        String deviceId = broker.register("tenant", "user").deviceId();
        AgentExecutionContext context = context();
        context.setExecutionDeadlineEpochMillis(System.currentTimeMillis() - 1000);
        ToolResult result = broker.execute(context, new DesktopExecutionTarget(deviceId, "workspace"), tool());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("截止时间");
        assertThat(broker.takePending("tenant", "user", deviceId)).isEmpty();
    }

    private CompletableFuture<ToolResult> submit(DesktopToolBroker broker, String deviceId) {
        return CompletableFuture.supplyAsync(() -> broker.execute(context(),
                new DesktopExecutionTarget(deviceId, "workspace"), tool()));
    }

    private DesktopToolCallVO awaitCall(DesktopToolBroker broker, String deviceId) {
        for (int index = 0; index < 2000; index++) {
            List<DesktopToolCallVO> calls = broker.takePending("tenant", "user", deviceId);
            if (!calls.isEmpty()) {
                return calls.getFirst();
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        throw new AssertionError("本机工具调用未入队");
    }

    private AgentExecutionContext context() {
        return AgentExecutionContext.builder().tenantId("tenant").userId("user").agentId("agent")
                .executionId("execution").build();
    }

    private ToolCall tool() {
        return ToolCall.builder().callId("model-call").toolCode("sys.read")
                .arguments(mapper.createObjectNode().put("path", "README.md")).build();
    }

    private static final class AdjustableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-19T00:00:00Z");

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
