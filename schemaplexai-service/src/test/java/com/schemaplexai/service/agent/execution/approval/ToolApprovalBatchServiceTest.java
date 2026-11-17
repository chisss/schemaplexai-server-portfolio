package com.schemaplexai.service.agent.execution.approval;

import com.schemaplexai.dao.mapper.ToolApprovalBatchMapper;
import com.schemaplexai.model.entity.ToolApprovalBatch;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolApprovalBatchServiceTest {

    private final ToolApprovalBatchMapper batchMapper = mock(ToolApprovalBatchMapper.class);
    private final ToolApprovalBatchService service = new ToolApprovalBatchService(batchMapper);

    @Test
    void shouldCreateBatchSnapshotWithPerCallStates() {
        ToolExecutionRequest paused = request("call-2", "sys.write");

        service.createBatch("tenant-1", "agent-1", "exec-1", "conv-1", 3,
                List.of(request("call-1", "sys.read"), paused, request("call-3", "sys.write")), "call-2");

        ArgumentCaptor<ToolApprovalBatch> captor = ArgumentCaptor.forClass(ToolApprovalBatch.class);
        verify(batchMapper).insert(captor.capture());
        ToolApprovalBatch inserted = captor.getValue();
        assertThat(inserted.getTenantId()).isEqualTo("tenant-1");
        assertThat(inserted.getExecutionId()).isEqualTo("exec-1");
        assertThat(inserted.getRoundNum()).isEqualTo(3);
        assertThat(inserted.getCallStates()).hasSize(3);
        assertThat(inserted.getCallStates().get(0).get("status"))
                .isEqualTo(ToolApprovalBatchService.CALL_STATUS_PENDING);
        assertThat(inserted.getCallStates().get(1).get("status"))
                .isEqualTo(ToolApprovalBatchService.CALL_STATUS_PENDING_APPROVAL);
        assertThat(inserted.getCallStates().get(2).get("status"))
                .isEqualTo(ToolApprovalBatchService.CALL_STATUS_PENDING);
    }

    @Test
    void shouldMarkOthersSkippedAfterRestore() {
        ToolApprovalBatch batch = new ToolApprovalBatch();
        batch.setId("batch-1");
        batch.setCallStates(new ArrayList<>(List.of(
                state("call-1", "sys.read", ToolApprovalBatchService.CALL_STATUS_PENDING),
                state("call-2", "sys.write", ToolApprovalBatchService.CALL_STATUS_PENDING_APPROVAL)
        )));
        when(batchMapper.selectById("batch-1")).thenReturn(batch);

        service.markOthersSkippedAfterRestore("batch-1", "call-2");

        verify(batchMapper).updateById(batch);
        assertThat(batch.getCallStates().get(0).get("status"))
                .isEqualTo(ToolApprovalBatchService.CALL_STATUS_RESTORED_SKIPPED);
        assertThat(batch.getCallStates().get(1).get("status"))
                .isEqualTo(ToolApprovalBatchService.CALL_STATUS_PENDING_APPROVAL);
    }

    @Test
    void shouldUpdateSingleCallStatus() {
        ToolApprovalBatch batch = new ToolApprovalBatch();
        batch.setId("batch-1");
        batch.setCallStates(new ArrayList<>(List.of(
                state("call-2", "sys.write", ToolApprovalBatchService.CALL_STATUS_PENDING_APPROVAL)
        )));
        when(batchMapper.selectById("batch-1")).thenReturn(batch);

        service.markCallStatus("batch-1", "call-2", ToolApprovalBatchService.CALL_STATUS_CONSUMED);

        verify(batchMapper).updateById(batch);
        assertThat(batch.getCallStates().get(0).get("status"))
                .isEqualTo(ToolApprovalBatchService.CALL_STATUS_CONSUMED);
    }

    private ToolExecutionRequest request(String callId, String name) {
        return ToolExecutionRequest.builder().id(callId).name(name).arguments("{}").build();
    }

    private Map<String, Object> state(String callId, String toolCode, String status) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("callId", callId);
        state.put("toolCode", toolCode);
        state.put("status", status);
        return state;
    }
}
