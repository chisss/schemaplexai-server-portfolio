package com.schemaplexai.service.agent.execution.steering;

import com.schemaplexai.dao.mapper.ExecutionSteeringMapper;
import com.schemaplexai.model.entity.ExecutionSteering;
import com.schemaplexai.service.memory.CompositeChatMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExecutionSteeringServiceTest {

    private final ExecutionSteeringMapper steeringMapper = mock(ExecutionSteeringMapper.class);
    private final CompositeChatMemoryStore archive = mock(CompositeChatMemoryStore.class);
    private final ExecutionSteeringService service = new ExecutionSteeringService(steeringMapper, archive);

    @Test
    void shouldDelegateSeqGenerationToAtomicInsert() {
        ExecutionSteering persisted = new ExecutionSteering();
        persisted.setSeq(5);
        when(steeringMapper.selectOne(any())).thenReturn(persisted);

        ExecutionSteering created = service.create("tenant-1", "exec-1", "补充一个约束");

        // seq 由 DB 原子生成（advisory lock + INSERT...SELECT MAX+1），服务端不再自算
        verify(steeringMapper).insertWithAutoSeq(any(), eq("tenant-1"), eq("exec-1"), eq("补充一个约束"));
        assertThat(created.getSeq()).isEqualTo(5);
        assertThat(created.getStatus()).isEqualTo(ExecutionSteeringService.STATUS_PENDING);
        assertThat(created.getExecutionId()).isEqualTo("exec-1");
        assertThat(created.getTenantId()).isEqualTo("tenant-1");
    }

    @Test
    void shouldFallbackSeqToOneWhenReadbackEmpty() {
        when(steeringMapper.selectOne(any())).thenReturn(null);

        ExecutionSteering created = service.create("tenant-1", "exec-1", "第一条引导");

        assertThat(created.getSeq()).isEqualTo(1);
    }

    @Test
    void shouldMarkAppliedExactlyOnce() {
        when(steeringMapper.update(any(ExecutionSteering.class), any())).thenReturn(1, 0);

        assertThat(service.markApplied("steering-1")).isTrue();
        assertThat(service.markApplied("steering-1")).isFalse();
    }

    @Test
    void shouldRejectMissingSteeringBeforeWriting() {
        assertThatThrownBy(() -> service.applyAndArchive(null, "conversation"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.applyAndArchive(new ExecutionSteering(), "conversation"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(steeringMapper, archive);
    }

    @Test
    void shouldSkipArchiveWhenPendingUpdateDidNotWin() {
        ExecutionSteering steering = new ExecutionSteering();
        steering.setId("steering-1");
        steering.setTenantId("tenant-1");
        steering.setExecutionId("exec-1");
        steering.setContent("继续处理");

        when(steeringMapper.update(any(ExecutionSteering.class), any())).thenReturn(0);

        assertThat(service.applyAndArchive(steering, "conversation")).isEmpty();
        verifyNoInteractions(archive);
    }

    @Test
    void shouldBuildSteeringWrapperWithPriorityAndTaskPreservationSemantics() {
        String wrapper = ExecutionSteeringService.buildSteeringWrapper("只做 A，不要做 B");

        assertThat(wrapper).contains("【用户最新引导】").contains("最高优先级").contains("保持不变").contains("只做 A，不要做 B");
    }

    @Test
    void shouldRejectBlankSteering() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.create("tenant-1", "exec-1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldReturnEmptyListForBlankExecutionId() {
        assertThat(service.findPending(" ")).isEqualTo(List.of());
    }
}
