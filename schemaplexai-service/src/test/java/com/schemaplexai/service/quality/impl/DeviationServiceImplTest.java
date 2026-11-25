package com.schemaplexai.service.quality.impl;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.converter.DeviationConverter;
import com.schemaplexai.model.dto.quality.DeviationDetectRequest;
import com.schemaplexai.model.dto.quality.DeviationQueryRequest;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.validator.DeviationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DeviationServiceImplTest {

    @Test
    void shouldSendOriginalTaskFromSameTenantExecution() {
        var messages = mock(RabbitTemplate.class);
        var executions = mock(AgentExecutionMapper.class);
        var service = new DeviationServiceImpl(mock(QualityDeviationMapper.class), mock(DeviationConverter.class),
                mock(DeviationValidator.class), new EntityValidator(), messages, executions,
                mock(SpecMapper.class), mock(WorkspaceMapper.class), mock(UserMapper.class));
        var execution = new AgentExecution();
        execution.setTenantId("tenant-1");
        execution.setInputPrompt("检查报价 CLI 并输出两位小数");
        execution.setOutputResult("构建通过，运行结果为 59.97");
        when(executions.selectById("exec-1")).thenReturn(execution);
        var request = new DeviationDetectRequest();
        request.setAgentExecutionId("exec-1");

        SecurityUtil.setCurrentTenantId("tenant-1");
        try {
            service.detect(request);
        } finally {
            SecurityUtil.clear();
        }

        verify(messages).convertAndSend(eq("sf.quality.check"), argThat((Object message) ->
                message instanceof Map<?, ?> fields
                        && "tenant-1".equals(fields.get("tenantId"))
                        && execution.getInputPrompt().equals(fields.get("originalTask"))
                        && execution.getOutputResult().equals(fields.get("targetContent"))));
    }

    @Test
    void shouldRejectExecutionFromAnotherTenantBeforeSendingQualityMessage() {
        var messages = mock(RabbitTemplate.class);
        var executions = mock(AgentExecutionMapper.class);
        var service = new DeviationServiceImpl(mock(QualityDeviationMapper.class), mock(DeviationConverter.class),
                mock(DeviationValidator.class), new EntityValidator(), messages, executions,
                mock(SpecMapper.class), mock(WorkspaceMapper.class), mock(UserMapper.class));
        var execution = new AgentExecution();
        execution.setTenantId("tenant-2");
        execution.setInputPrompt("另一个租户的原始任务");
        execution.setOutputResult("另一个租户的执行结果");
        when(executions.selectById("exec-1")).thenReturn(execution);
        var request = new DeviationDetectRequest();
        request.setAgentExecutionId("exec-1");

        SecurityUtil.setCurrentTenantId("tenant-1");
        try {
            assertThatThrownBy(() -> service.detect(request))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.getCode()).isEqualTo(ResultCode.AGENT_EXECUTION_NOT_FOUND.getCode()));
        } finally {
            SecurityUtil.clear();
        }

        verifyNoInteractions(messages);
    }

    @Test
    void shouldReturnEmptyPageWithoutQueryingDeviationIdsWhenWorkspaceHasNoSpecs() {
        var deviations = mock(QualityDeviationMapper.class);
        var specs = mock(SpecMapper.class);
        when(specs.selectList(any())).thenReturn(List.of());
        var service = new DeviationServiceImpl(deviations, mock(DeviationConverter.class),
                mock(DeviationValidator.class), mock(EntityValidator.class), mock(RabbitTemplate.class),
                mock(AgentExecutionMapper.class), specs, mock(WorkspaceMapper.class), mock(UserMapper.class));
        var query = new DeviationQueryRequest();
        query.setWorkspaceId("868c9f4d-8113-4f30-abf5-da465b24be3f");
        query.setPage(2);
        query.setSize(10);

        var result = service.page(query);

        assertThat(result.getRecords()).isEmpty();
        assertThat(result.getTotal()).isZero();
        assertThat(result.getCurrent()).isEqualTo(2);
        assertThat(result.getSize()).isEqualTo(10);
        verifyNoInteractions(deviations);
    }
}
