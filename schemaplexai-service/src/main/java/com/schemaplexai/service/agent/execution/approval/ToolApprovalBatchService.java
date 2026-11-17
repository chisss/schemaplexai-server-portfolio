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

    private final ToolApprovalBatchMapper batchMapper;

    /**
     * 创建批次快照：remainingRequests 为暂停点起该轮尚未处理的全部工具请求
     */
    public ToolApprovalBatch createBatch(String tenantId, String agentId, String executionId, String conversationId,
                                         int roundNum, List<ToolExecutionRequest> remainingRequests, String pausedCallId) {
        List<Map<String, Object>> callStates = new ArrayList<>();
        for (ToolExecutionRequest request : remainingRequests) {
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("callId", request.id());
            state.put("toolCode", request.name());
            state.put("status", request.id() != null && request.id().equals(pausedCallId)
                    ? CALL_STATUS_PENDING_APPROVAL
                    : CALL_STATUS_PENDING);
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
