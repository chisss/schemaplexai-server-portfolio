package com.schemaplexai.service.desktop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.model.ToolResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.dto.desktop.DesktopToolResultRequest;
import com.schemaplexai.model.vo.desktop.DesktopDeviceVO;
import com.schemaplexai.model.vo.desktop.DesktopToolCallVO;
import com.schemaplexai.service.agent.execution.AgentExecutionContext;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.tool.model.ToolCall;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单节点、内存有界的本机工具桥。调用仅投递一次，超时或进程重启后不重发写操作。
 * 多节点部署须固定会话到同一节点；持久化消息与跨节点恢复需独立实现。
 */
@Service
public class DesktopToolBroker {

    private static final int MAX_DEVICES = 256;
    private static final int MAX_DEVICES_PER_USER = 8;
    private static final int MAX_CALLS_PER_DEVICE = 32;
    private static final int MAX_CALLS = 128;
    private static final int MAX_BUFFERED_ARGUMENT_CHARS = 16 * 1024 * 1024;
    private static final int MAX_ARGUMENT_CHARS = 2 * 1024 * 1024;
    private static final int MAX_RESULT_CHARS = 256 * 1024;
    private static final Duration DEVICE_IDLE_TIMEOUT = Duration.ofMinutes(5);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration callTimeout;
    private final AgentLogService agentLogService;
    private final Map<String, Device> devices = new HashMap<>();

    @Autowired
    public DesktopToolBroker(ObjectMapper objectMapper, AgentLogService agentLogService) {
        this(objectMapper, Clock.systemUTC(), Duration.ofSeconds(120), agentLogService);
    }

    DesktopToolBroker(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC(), Duration.ofSeconds(120));
    }

    DesktopToolBroker(ObjectMapper objectMapper, Clock clock, Duration callTimeout) {
        this(objectMapper, clock, callTimeout, null);
    }

    DesktopToolBroker(ObjectMapper objectMapper, Clock clock, Duration callTimeout, AgentLogService agentLogService) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.callTimeout = callTimeout;
        this.agentLogService = agentLogService;
    }

    public synchronized DesktopDeviceVO register(String tenantId, String userId) {
        return register(tenantId, userId, false, false);
    }

    public synchronized DesktopDeviceVO register(String tenantId, String userId, boolean supportsExactEdit) {
        return register(tenantId, userId, supportsExactEdit, false);
    }

    public synchronized DesktopDeviceVO register(String tenantId, String userId, boolean supportsExactEdit,
                                                 boolean supportsReadRange) {
        requireIdentity(tenantId, userId);
        expireDevices();
        long ownedDevices = devices.values().stream().filter(device -> device.isOwnedBy(tenantId, userId)).count();
        if (devices.size() >= MAX_DEVICES || ownedDevices >= MAX_DEVICES_PER_USER) {
            throw new BusinessException(429, "桌面设备数量已达上限，请先注销空闲设备");
        }
        String id = UUID.randomUUID().toString();
        devices.put(id, new Device(tenantId, userId, clock.instant(), supportsExactEdit, supportsReadRange));
        return new DesktopDeviceVO(id);
    }

    public synchronized boolean supportsExactEdit(String tenantId, String userId, String deviceId) {
        Device device = activeOwnedDevice(tenantId, userId, deviceId);
        return device != null && device.supportsExactEdit;
    }

    public synchronized boolean supportsReadRange(String tenantId, String userId, String deviceId) {
        Device device = activeOwnedDevice(tenantId, userId, deviceId);
        return device != null && device.supportsReadRange;
    }

    private Device activeOwnedDevice(String tenantId, String userId, String deviceId) {
        Device device = devices.get(deviceId);
        return device != null && device.isOwnedBy(tenantId, userId)
                && !device.lastSeen.plus(DEVICE_IDLE_TIMEOUT).isBefore(clock.instant()) ? device : null;
    }

    public synchronized void validateTarget(String tenantId, String userId, DesktopExecutionTarget target) {
        if (target != null) {
            requireDevice(tenantId, userId, target.deviceId());
        }
    }

    public synchronized List<DesktopToolCallVO> takePending(String tenantId, String userId, String deviceId) {
        Device device = requireDevice(tenantId, userId, deviceId);
        device.lastSeen = clock.instant();
        List<DesktopToolCallVO> pending = new ArrayList<>();
        while (!device.queue.isEmpty()) {
            PendingCall call = device.calls.get(device.queue.removeFirst());
            if (call != null && call.request.expiresAt().isAfter(clock.instant()) && !call.result.isDone()) {
                call.delivered = true;
                pending.add(call.request);
            }
        }
        return pending;
    }

    public synchronized void complete(String tenantId, String userId, String deviceId, String callId,
                                      DesktopToolResultRequest result) {
        Device device = requireDevice(tenantId, userId, deviceId);
        PendingCall call = requireLiveDeliveredCall(device, callId);
        if (result == null || result.success() == null || (result.output() != null
                && result.output().toString().length() > MAX_RESULT_CHARS)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "工具结果缺失或超过 256KiB 限制");
        }
        device.lastSeen = clock.instant();
        call.result.complete(result);
    }

    /** 执行前再次核验，避免已取消任务的旧确认框继续发起本机操作。 */
    public synchronized void validateCall(String tenantId, String userId, String deviceId, String callId) {
        requireLiveDeliveredCall(requireDevice(tenantId, userId, deviceId), callId);
    }

    private PendingCall requireLiveDeliveredCall(Device device, String callId) {
        PendingCall call = device.calls.get(callId);
        if (call == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "本机工具调用不存在或已结束");
        }
        if (!call.delivered || !call.request.expiresAt().isAfter(clock.instant()) || call.result.isDone()) {
            throw new BusinessException(409, "本机工具调用未投递、已过期或已回传");
        }
        return call;
    }

    public synchronized void unregister(String tenantId, String userId, String deviceId) {
        Device device = requireDevice(tenantId, userId, deviceId);
        devices.remove(deviceId);
        device.close("桌面设备已断开，未确认操作请检查本机状态");
    }

    public synchronized void cancelExecution(String tenantId, String userId, String executionId) {
        requireIdentity(tenantId, userId);
        for (Device device : devices.values()) {
            if (!device.isOwnedBy(tenantId, userId)) {
                continue;
            }
            device.cancelledExecutions.entrySet().removeIf(entry -> entry.getValue().isBefore(clock.instant()));
            if (device.cancelledExecutions.size() >= 256) {
                device.cancelledExecutions.remove(device.cancelledExecutions.keySet().iterator().next());
            }
            device.cancelledExecutions.put(executionId, clock.instant().plus(callTimeout));
            device.calls.entrySet().removeIf(entry -> {
                PendingCall call = entry.getValue();
                if (executionId.equals(call.request.executionId()) || executionId.equals(call.request.parentExecutionId())) {
                    call.result.completeExceptionally(new IllegalStateException("任务已取消；已开始的本机操作请检查本机状态"));
                    device.queue.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        }
    }

    public ToolResult execute(AgentExecutionContext context, DesktopExecutionTarget target, ToolCall toolCall) {
        boolean tracked = StringUtils.hasText(context.getDesktopVerificationCommand())
                && switch (toolCall.getToolCode()) {
                    case "sys.bash", "sys.write", "sys.edit", "sys.mkdir" -> true;
                    default -> false;
                };
        if (tracked) {
            // 持久化失败时停止派发，避免旧验证回执覆盖未记录的写操作。
            agentLogService.recordDesktopVerification(context, toolCall.getToolCode(), null);
        }
        PendingCall pending;
        Instant startedAt = Instant.now();
        try {
            pending = enqueue(context, target, toolCall);
        } catch (BusinessException exception) {
            return failure(toolCall, exception.getMessage());
        }
        ToolResult outcome = awaitResult(pending, target, toolCall);
        if (tracked) {
            recordVerification(context, pending.request, outcome, startedAt);
        }
        return outcome;
    }

    private ToolResult awaitResult(PendingCall pending, DesktopExecutionTarget target, ToolCall toolCall) {
        try {
            long remainingMillis = Math.max(1, Duration.between(clock.instant(), pending.request.expiresAt()).toMillis());
            DesktopToolResultRequest result = pending.result.get(remainingMillis, TimeUnit.MILLISECONDS);
            return ToolResult.builder().callId(toolCall.getCallId()).toolCode(toolCall.getToolCode())
                    .success(Boolean.TRUE.equals(result.success()))
                    .result(result.output() == null ? objectMapper.nullNode() : result.output())
                    .errorMessage(result.error())
                    .desktopCallId(UUID.fromString(pending.request.callId()))
                    .build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure(toolCall, "本机工具等待已取消，操作状态未知，请检查本机后重试");
        } catch (TimeoutException exception) {
            return failure(toolCall, "本机工具执行超时，操作状态未知；未自动重发，请检查本机结果");
        } catch (ExecutionException exception) {
            return failure(toolCall, exception.getCause().getMessage());
        } finally {
            removeCall(target.deviceId(), pending.request.callId());
        }
    }

    private void recordVerification(AgentExecutionContext context, DesktopToolCallVO call,
                                    ToolResult result, Instant startedAt) {
        var arguments = call.arguments();
        String workdir = arguments.path("workingDirectory").asText(arguments.path("workdir").asText("."));
        boolean verifier = "sys.bash".equals(call.toolCode()) && ".".equals(workdir)
                && context.getDesktopVerificationCommand().equals(arguments.path("command").asText());
        agentLogService.recordDesktopVerification(context, call.toolCode(),
                verifier && successfulShell(result) ? startedAt : null);
    }

    private boolean successfulShell(ToolResult result) {
        if (!result.isSuccess() || result.getResult() == null) {
            return false;
        }
        try {
            JsonNode output = result.getResult();
            JsonNode payload = output.isTextual() ? objectMapper.readTree(output.asText()) : output;
            JsonNode exitCode = payload == null ? null : payload.get("exitCode");
            return exitCode != null && exitCode.isIntegralNumber() && exitCode.bigIntegerValue().signum() == 0;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private synchronized PendingCall enqueue(AgentExecutionContext context, DesktopExecutionTarget target,
                                             ToolCall toolCall) {
        Device device = requireDevice(context.getTenantId(), context.getUserId(), target.deviceId());
        if (!StringUtils.hasText(context.getExecutionId()) || !StringUtils.hasText(context.getAgentId())
                || toolCall == null || !StringUtils.hasText(toolCall.getCallId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "本机工具缺少执行关联信息");
        }
        device.cancelledExecutions.entrySet().removeIf(entry -> entry.getValue().isBefore(clock.instant()));
        if (device.cancelledExecutions.containsKey(context.getExecutionId())
                || device.cancelledExecutions.containsKey(context.getParentExecutionId())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务已取消，禁止继续派发本机工具");
        }
        JsonNode arguments = toolCall.getArguments();
        int argumentChars = arguments == null ? 0 : arguments.toString().length();
        if (arguments == null || !arguments.isObject() || argumentChars > MAX_ARGUMENT_CHARS) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "本机工具参数无效或超过 2MiB 限制");
        }
        if ("sys.read".equals(toolCall.getToolCode()) && DesktopToolSchema.hasReadRange(arguments)) {
            if (!device.supportsReadRange) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "当前设备仅支持读取完整文件，请省略 startLine 和 endLine");
            }
            DesktopToolSchema.validateReadRange(arguments);
        }
        long callCount = devices.values().stream().mapToInt(entry -> entry.calls.size()).sum();
        int bufferedChars = devices.values().stream().flatMap(entry -> entry.calls.values().stream())
                .mapToInt(entry -> entry.argumentChars).sum();
        if (device.calls.size() >= MAX_CALLS_PER_DEVICE || callCount >= MAX_CALLS
                || bufferedChars + argumentChars > MAX_BUFFERED_ARGUMENT_CHARS) {
            throw new BusinessException(429, "本机工具等待队列已满");
        }
        String id = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(callTimeout);
        if (context.getExecutionDeadlineEpochMillis() != null) {
            Instant deadline = Instant.ofEpochMilli(context.getExecutionDeadlineEpochMillis());
            expiresAt = deadline.isBefore(expiresAt) ? deadline : expiresAt;
        }
        if (!expiresAt.isAfter(clock.instant())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "任务截止时间已过，禁止派发本机工具");
        }
        DesktopToolCallVO request = new DesktopToolCallVO(id, context.getExecutionId(), context.getParentExecutionId(), context.getAgentId(),
                target.deviceId(), target.workspaceId(), toolCall.getToolCode(), arguments.deepCopy(),
                expiresAt);
        PendingCall call = new PendingCall(request, argumentChars);
        device.calls.put(id, call);
        device.queue.addLast(id);
        return call;
    }

    private synchronized void removeCall(String deviceId, String callId) {
        Device device = devices.get(deviceId);
        if (device != null) {
            device.calls.remove(callId);
            device.queue.remove(callId);
        }
    }

    private Device requireDevice(String tenantId, String userId, String deviceId) {
        requireIdentity(tenantId, userId);
        Device device = devices.get(deviceId);
        if (device == null || !device.isOwnedBy(tenantId, userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "设备不可用或不属于当前用户");
        }
        if (device.lastSeen.plus(DEVICE_IDLE_TIMEOUT).isBefore(clock.instant())) {
            devices.remove(deviceId);
            device.close("桌面设备离线");
            throw new BusinessException(ResultCode.FORBIDDEN, "桌面设备已离线，请重新登记");
        }
        return device;
    }

    private void requireIdentity(String tenantId, String userId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "缺少设备用户或租户身份");
        }
    }

    private void expireDevices() {
        devices.entrySet().removeIf(entry -> {
            Device device = entry.getValue();
            if (device.lastSeen.plus(DEVICE_IDLE_TIMEOUT).isBefore(clock.instant())) {
                device.close("桌面设备离线");
                return true;
            }
            return false;
        });
    }

    private ToolResult failure(ToolCall call, String message) {
        return ToolResult.builder().callId(call.getCallId()).toolCode(call.getToolCode()).success(false)
                .result(objectMapper.nullNode()).errorMessage(message).build();
    }

    private static final class Device {
        private final String tenantId;
        private final String userId;
        private final boolean supportsExactEdit;
        private final boolean supportsReadRange;
        private final Map<String, PendingCall> calls = new HashMap<>();
        private final Map<String, Instant> cancelledExecutions = new LinkedHashMap<>();
        private final ArrayDeque<String> queue = new ArrayDeque<>();
        private Instant lastSeen;

        private Device(String tenantId, String userId, Instant lastSeen, boolean supportsExactEdit,
                       boolean supportsReadRange) {
            this.tenantId = tenantId;
            this.userId = userId;
            this.lastSeen = lastSeen;
            this.supportsExactEdit = supportsExactEdit;
            this.supportsReadRange = supportsReadRange;
        }

        private boolean isOwnedBy(String tenantId, String userId) {
            return this.tenantId.equals(tenantId) && this.userId.equals(userId);
        }

        private void close(String reason) {
            calls.values().forEach(call -> call.result.completeExceptionally(new IllegalStateException(reason)));
            calls.clear();
            queue.clear();
        }
    }

    private static final class PendingCall {
        private final DesktopToolCallVO request;
        private final int argumentChars;
        private final CompletableFuture<DesktopToolResultRequest> result = new CompletableFuture<>();
        private boolean delivered;

        private PendingCall(DesktopToolCallVO request, int argumentChars) {
            this.request = request;
            this.argumentChars = argumentChars;
        }
    }
}
