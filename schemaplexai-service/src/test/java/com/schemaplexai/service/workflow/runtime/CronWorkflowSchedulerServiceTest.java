package com.schemaplexai.service.workflow.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CronWorkflowSchedulerServiceTest {

    @Test
    void shouldCreateAndStartWorkflowWhenCronTemplateIsDue() {
        WorkflowTemplateMapper templateMapper = mock(WorkflowTemplateMapper.class);
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowInstanceService workflowInstanceService = mock(WorkflowInstanceService.class);
        ObjectProvider<WorkflowInstanceService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(workflowInstanceService);
        when(instanceMapper.selectList(any())).thenReturn(List.of());

        WorkflowInstanceVO created = new WorkflowInstanceVO();
        created.setId("wf-cron-1");
        when(workflowInstanceService.create(any())).thenReturn(created);

        CronWorkflowSchedulerService schedulerService = new CronWorkflowSchedulerService(
                templateMapper,
                instanceMapper,
                provider,
                new ObjectMapper()
        );
        WorkflowTemplate template = buildTemplate(
                "{\"workspaceId\":\"workspace-1\",\"scanMode\":\"full\"}"
        );

        schedulerService.triggerTemplateIfDue(template);

        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(WorkflowInstanceCreateRequest.class);
        verify(workflowInstanceService).create(captor.capture());
        verify(workflowInstanceService).start("wf-cron-1");

        WorkflowInstanceCreateRequest request = captor.getValue();
        assertThat(request.getTenantId()).isEqualTo("tenant-1");
        assertThat(request.getTemplateId()).isEqualTo("tpl-cron-1");
        assertThat(request.getVariables())
                .containsEntry("triggerType", "cron")
                .containsEntry("workspaceId", "workspace-1")
                .containsEntry("scanMode", "full");
        assertThat(request.getVariables()).containsKey("cronFireAt");
    }

    @Test
    void shouldSkipTemplateWhenCronWindowAlreadyTriggered() {
        WorkflowTemplateMapper templateMapper = mock(WorkflowTemplateMapper.class);
        WorkflowInstanceMapper instanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowInstanceService workflowInstanceService = mock(WorkflowInstanceService.class);
        ObjectProvider<WorkflowInstanceService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(workflowInstanceService);

        CronWorkflowSchedulerService schedulerService = new CronWorkflowSchedulerService(
                templateMapper,
                instanceMapper,
                provider,
                new ObjectMapper()
        );
        WorkflowTemplate template = buildTemplate(Map.of("workspaceId", "workspace-1"));
        ZonedDateTime fireTime = schedulerService.resolveDueFireTime(
                "* * * * *",
                ZonedDateTime.now(ZoneId.of("UTC"))
        );
        WorkflowInstance existing = new WorkflowInstance();
        existing.setVariables(Map.of("cronFireAt", fireTime.withNano(0).format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)));
        when(instanceMapper.selectList(any())).thenReturn(List.of(existing));

        schedulerService.triggerTemplateIfDue(template);

        verify(workflowInstanceService, never()).create(any());
        verify(workflowInstanceService, never()).start(any());
    }

    @Test
    void shouldNormalizeFiveSegmentCronExpression() {
        CronWorkflowSchedulerService schedulerService = new CronWorkflowSchedulerService(
                mock(WorkflowTemplateMapper.class),
                mock(WorkflowInstanceMapper.class),
                mock(ObjectProvider.class),
                new ObjectMapper()
        );

        String normalized = schedulerService.normalizeCronExpression("0 2 * * *");

        assertThat(normalized).isEqualTo("0 0 2 * * *");
    }

    private WorkflowTemplate buildTemplate(Object triggerInputs) {
        WorkflowTemplate template = new WorkflowTemplate();
        template.setId("tpl-cron-1");
        template.setTenantId("tenant-1");
        template.setName("定时安全扫描");
        template.setStatus("published");
        template.setDefinition(Map.of(
                "nodes", List.of(
                        Map.of(
                                "id", "n1",
                                "type", "trigger_cron",
                                "label", "每分钟触发",
                                "config", Map.of(
                                        "cronExpression", "* * * * *",
                                        "timezone", "UTC",
                                        "triggerInputs", triggerInputs
                                )
                        ),
                        Map.of(
                                "id", "n2",
                                "type", "end",
                                "label", "结束",
                                "config", Map.of()
                        )
                ),
                "edges", List.of(
                        Map.of(
                                "id", "e1",
                                "source", "n1",
                                "target", "n2"
                        )
                )
        ));
        return template;
    }
}
