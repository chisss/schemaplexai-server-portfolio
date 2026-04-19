package com.schemaplexai.service.workflow.runtime;

import com.schemaplexai.common.enums.MessageTemplateTypeEnum;
import com.schemaplexai.dao.mapper.MessageTemplateMapper;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.entity.MessageTemplate;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.service.integration.notification.NotificationDispatchService;
import com.schemaplexai.service.integration.notification.model.NotificationDispatchRequest;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowNotificationServiceTest {

    @Test
    void shouldBuildRelativeReviewPathWhenFrontendBaseUrlMissing() {
        WorkflowNotificationService service = new WorkflowNotificationService(
                mock(NotificationChannelMapper.class),
                mock(MessageTemplateMapper.class),
                mock(WorkflowTemplateMapper.class),
                mock(SpecMapper.class),
                mock(NotificationDispatchService.class)
        );

        ReflectionTestUtils.setField(service, "frontendBaseUrl", "");

        assertThat(service.buildSpecReviewActionPath("spec-1", "instance-1", "review-1", "session-1"))
                .isEqualTo("/approval-center?type=workflow_review&id=session-1&specId=spec-1&instanceId=instance-1&nodeId=review-1");
        assertThat(service.buildSpecReviewActionUrl("spec-1", "instance-1", "review-1", null))
                .isEqualTo("/approval-center?type=workflow_review&specId=spec-1&instanceId=instance-1&nodeId=review-1");
    }

    @Test
    void shouldRenderCompletedStatusForWorkflowCompletedTemplate() {
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        MessageTemplateMapper messageTemplateMapper = mock(MessageTemplateMapper.class);
        WorkflowTemplateMapper workflowTemplateMapper = mock(WorkflowTemplateMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        NotificationDispatchService notificationDispatchService = mock(NotificationDispatchService.class);

        WorkflowNotificationService service = new WorkflowNotificationService(
                notificationChannelMapper,
                messageTemplateMapper,
                workflowTemplateMapper,
                specMapper,
                notificationDispatchService
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-1");
        channel.setName("飞书");
        channel.setChannelType("feishu");
        when(notificationChannelMapper.selectById("channel-1")).thenReturn(channel);

        MessageTemplate template = new MessageTemplate();
        template.setId("template-1");
        template.setName("工作流完成通知");
        template.setTemplateType(MessageTemplateTypeEnum.WORKFLOW_COMPLETED.getCode());
        template.setTitleTemplate("状态:${workflowStatus}");
        template.setContentTemplate("工作流=${workflowStatus}");
        when(messageTemplateMapper.selectById("template-1")).thenReturn(template);

        WorkflowTemplate workflowTemplate = new WorkflowTemplate();
        workflowTemplate.setId("wf-template-1");
        workflowTemplate.setName("标准研发工作流");
        when(workflowTemplateMapper.selectById("wf-template-1")).thenReturn(workflowTemplate);

        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setName("工作流回归");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        when(notificationDispatchService.send(any(NotificationDispatchRequest.class)))
                .thenReturn(NotificationSendResult.builder()
                        .recordId("record-1")
                        .responseSummary("ok")
                        .build());

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTemplateId("wf-template-1");
        instance.setSpecId("spec-1");
        instance.setName("实例-1");
        instance.setStatus("running");
        instance.setVariables(Map.of(
                "artifactDeliveryType", "feishu_doc",
                "artifactDeliveryUrl", "https://feishu.cn/docx/doxcn-test-doc",
                "artifactDeliveryDocumentId", "doxcn-test-doc"
        ));

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-1");
        nodeExecution.setNodeLabel("发送完成通知");

        service.sendWorkflowCompletedNotification(instance, nodeExecution, Map.of(
                "channelId", "channel-1",
                "messageTemplateId", "template-1"
        ));

        ArgumentCaptor<NotificationDispatchRequest> requestCaptor = ArgumentCaptor.forClass(NotificationDispatchRequest.class);
        verify(notificationDispatchService).send(requestCaptor.capture());

        NotificationDispatchRequest request = requestCaptor.getValue();
        assertThat(request.getMessage().getTitle()).contains("completed");
        assertThat(request.getMessage().getContent()).contains("completed");
        assertThat(request.getMessage().getPreviewUrl()).isEqualTo("https://feishu.cn/docx/doxcn-test-doc");
    }
}
