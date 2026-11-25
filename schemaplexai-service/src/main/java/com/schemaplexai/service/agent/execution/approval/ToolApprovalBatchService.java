package com.schemaplexai.service.agent.execution.approval;

import com.schemaplexai.dao.mapper.ToolApprovalBatchMapper;
import com.schemaplexai.model.entity.ToolApprovalBatch;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具审批批次账本：暂停时持久化该轮整批 toolCalls 及每个 callId 的状态，
 * 恢复消费后显式记账（防重复执行 + 可审计）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolApprovalBatchService {

    public static final String CALL_STATUS_PENDING = "pending";
    public static final String CALL_STATUS_PENDING_APPROVAL = "pending_approval";
    public static final String CALL_STATUS_CONSUMED = "consumed";
    public static final String CALL_STATUS_RESTORED_SKIPPED = "restored_skipped";
    public static final String CALL_STATUS_DISPATCHING = "dispatching";
    public static final String CALL_STATUS_RESULT = "result";
    public static final String CALL_STATUS_REJECTED = "rejected";
    public static final String CALL_STATUS_LIMITED = "limited";
    public static final String CALL_STATUS_SUGGESTED = "suggested";

    private final ToolApprovalBatchMapper batchMapper;

    /**
     * 按模型提供的顺序记录整轮请求，初始状态由执行策略和每轮配额决定。
     */
    public ToolApprovalBatch createBatch(String tenantId, String agentId, String executionId, String conversationId,
                                         int roundNum, List<ToolExecutionRequest> remainingRequests, String pausedCallId) {
        return createBatch(tenantId, agentId, executionId, conversationId, roundNum, remainingRequests,
                pausedCallId == null ? Map.of() : Map.of(pausedCallId, CALL_STATUS_PENDING_APPROVAL));
    }

    public ToolApprovalBatch createBatch(String tenantId, String agentId, String executionId, String conversationId,
                                         int roundNum, List<ToolExecutionRequest> requests, Map<String, String> statuses) {
        List<Map<String, Object>> callStates = new ArrayList<>();
        for (ToolExecutionRequest request : requests) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("callId", request.id());
            state.put("toolCode", request.name());
            state.put("arguments", request.arguments() != null ? request.arguments() : "{}");
            state.put("status", request.id() == null ? CALL_STATUS_PENDING
                    : statuses.getOrDefault(request.id(), CALL_STATUS_PENDING));
            callStates.add(state);
        }
        ToolApprovalBatch batch = new ToolApprovalBatch();
        batch.setTenantId(tenantId);
        batch.setAgentId(agentId);
        batch.setExecutionId(executionId);
        batch.setConversationId(conversationId);
        batch.setRoundNum(roundNum);
        batch.setCallStates(callStates);
        batchMapper.insert(batch);
        log.info("创建工具审批批次: batchId={}, executionId={}, round={}, callCount={}",
                batch.getId(), executionId, roundNum, callStates.size());
        return batch;
    }

    public ToolApprovalBatch findForApproval(String batchId, String executionId, String tenantId) {
        if (!StringUtils.hasText(batchId)) {
            return null;
        }
        ToolApprovalBatch batch = batchMapper.selectById(batchId);
        if (batch == null || !executionId.equals(batch.getExecutionId()) || !tenantId.equals(batch.getTenantId())) {
            return null;
        }
        return batch;
    }

    public boolean isResumable(ToolApprovalBatch batch) {
        return batch != null && batch.getCallStates() != null && !batch.getCallStates().isEmpty()
                && batch.getCallStates().stream().allMatch(call -> call.containsKey("arguments"));
    }

    public ToolExecutionRequest request(Map<String, Object> call) {
        return ToolExecutionRequest.builder()
                .id((String) call.get("callId"))
                .name((String) call.get("toolCode"))
                .arguments((String) call.get("arguments"))
                .build();
    }

    public void record(ToolApprovalBatch batch, Map<String, Object> call, String status) {
        call.put("status", status);
        save(batch);
    }

    public void linkApproval(ToolApprovalBatch batch, String callId, String approvalId) {
        var call = batch.getCallStates().stream().filter(state -> callId.equals(state.get("callId")))
                .findFirst().orElseThrow();
        call.put("approvalId", approvalId);
        record(batch, call, CALL_STATUS_PENDING_APPROVAL);
    }

    public void recordResult(ToolApprovalBatch batch, Map<String, Object> call, String text,
                             boolean error, String controlAction) {
        call.put("resultText", text);
        call.put("error", error);
        call.put("controlAction", controlAction);
        record(batch, call, CALL_STATUS_RESULT);
    }

    private void save(ToolApprovalBatch batch) {
        if (batchMapper.updateById(batch) != 1) {
            throw new IllegalStateException("审批工具批次写入失败: " + batch.getId());
        }
    }

    /**
     * 更新单个 callId 状态
     */
    public void markCallStatus(String batchId, String callId, String status) {
        if (!StringUtils.hasText(batchId) || !StringUtils.hasText(callId)) {
            return;
        }
        ToolApprovalBatch batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getCallStates() == null) {
            return;
        }
        for (Map<String, Object> state : batch.getCallStates()) {
            if (callId.equals(state.get("callId"))) {
                state.put("status", status);
            }
        }
        batchMapper.updateById(batch);
    }

    /**
     * 恢复消费后：批次内其余未决策 callId 显式记账（restored_skipped）
     */
    public void markOthersSkippedAfterRestore(String batchId, String consumedCallId) {
        if (!StringUtils.hasText(batchId)) {
            return;
        }
        ToolApprovalBatch batch = batchMapper.selectById(batchId);
        if (batch == null || batch.getCallStates() == null) {
            return;
        }
        for (Map<String, Object> state : batch.getCallStates()) {
            String callId = String.valueOf(state.get("callId"));
            if (consumedCallId.equals(callId)) {
                continue;
            }
            String status = String.valueOf(state.get("status"));
            if (CALL_STATUS_PENDING.equals(status) || CALL_STATUS_PENDING_APPROVAL.equals(status)) {
                state.put("status", CALL_STATUS_RESTORED_SKIPPED);
            }
        }
        batchMapper.updateById(batch);
    }
}
