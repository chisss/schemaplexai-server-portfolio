package com.schemaplexai.service.agent.execution.steering;

import com.schemaplexai.dao.mapper.ExecutionSteeringMapper;
import com.schemaplexai.model.entity.ExecutionSteering;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionSteeringServiceTest {

    private final ExecutionSteeringMapper steeringMapper = mock(ExecutionSteeringMapper.class);
    private final ExecutionSteeringService service = new ExecutionSteeringService(steeringMapper);

    @Test
    void shouldAssignMonotonicSeqPerExecution() {
        ExecutionSteering latest = new ExecutionSteering();
        latest.setSeq(3);
        when(steeringMapper.selectOne(any())).thenReturn(latest);

        ExecutionSteering created = service.create("tenant-1", "exec-1", "补充一个约束");

        ArgumentCaptor<ExecutionSteering> captor = ArgumentCaptor.forClass(ExecutionSteering.class);
        verify(steeringMapper).insert(captor.capture());
        ExecutionSteering inserted = captor.getValue();
        assertThat(inserted.getSeq()).isEqualTo(4);
        assertThat(inserted.getStatus()).isEqualTo(ExecutionSteeringService.STATUS_PENDING);
        assertThat(inserted.getExecutionId()).isEqualTo("exec-1");
        assertThat(inserted.getTenantId()).isEqualTo("tenant-1");
        assertThat(created).isSameAs(inserted);
    }

    @Test
    void shouldStartSeqAtOneWhenNoPriorSteering() {
        when(steeringMapper.selectOne(any())).thenReturn(null);

        service.create("tenant-1", "exec-1", "第一条引导");

        ArgumentCaptor<ExecutionSteering> captor = ArgumentCaptor.forClass(ExecutionSteering.class);
        verify(steeringMapper).insert(captor.capture());
        assertThat(captor.getValue().getSeq()).isEqualTo(1);
    }

    @Test
    void shouldMarkAppliedExactlyOnce() {
        when(steeringMapper.update(any(ExecutionSteering.class), any())).thenReturn(1, 0);

        assertThat(service.markApplied("steering-1")).isTrue();
        assertThat(service.markApplied("steering-1")).isFalse();
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
