package com.schemaplexai.service.workflow.runtime;

import com.schemaplexai.common.enums.MessageTemplateTypeEnum;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.MessageTemplateMapper;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.ToolExecutionLogMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.MessageTemplate;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.ToolExecutionLog;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.monitor.AgentTraceFailureCategoryVO;
import com.schemaplexai.model.vo.monitor.AgentTraceFailureSummaryVO;
import com.schemaplexai.service.integration.notification.NotificationDispatchService;
import com.schemaplexai.service.integration.notification.model.NotificationDispatchRequest;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import com.schemaplexai.service.monitor.AgentTraceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
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
                mock(NotificationDispatchService.class),
                mock(AgentExecutionMapper.class),
                mock(ToolExecutionLogMapper.class),
                mock(AgentTraceService.class)
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
                notificationDispatchService,
                mock(AgentExecutionMapper.class),
                mock(ToolExecutionLogMapper.class),
                mock(AgentTraceService.class)
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

    @Test
    void shouldRenderInlineNotificationTemplateVariables() {
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        WorkflowTemplateMapper workflowTemplateMapper = mock(WorkflowTemplateMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        NotificationDispatchService notificationDispatchService = mock(NotificationDispatchService.class);

        WorkflowNotificationService service = new WorkflowNotificationService(
                notificationChannelMapper,
                mock(MessageTemplateMapper.class),
                workflowTemplateMapper,
                specMapper,
                notificationDispatchService,
                mock(AgentExecutionMapper.class),
                mock(ToolExecutionLogMapper.class),
                mock(AgentTraceService.class)
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-2");
        channel.setName("飞书");
        channel.setChannelType("feishu");
        when(notificationChannelMapper.selectById("channel-2")).thenReturn(channel);

        WorkflowTemplate workflowTemplate = new WorkflowTemplate();
        workflowTemplate.setId("wf-template-2");
        workflowTemplate.setName("SchemaPlexAI演示-财税协同闭环-闭环工作流");
        when(workflowTemplateMapper.selectById("wf-template-2")).thenReturn(workflowTemplate);

        Spec spec = new Spec();
        spec.setId("spec-2");
        spec.setName("财税协同闭环");
        when(specMapper.selectById("spec-2")).thenReturn(spec);

        when(notificationDispatchService.send(any(NotificationDispatchRequest.class)))
                .thenReturn(NotificationSendResult.builder()
                        .recordId("record-2")
                        .responseSummary("ok")
                        .build());

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-2");
        instance.setTemplateId("wf-template-2");
        instance.setSpecId("spec-2");
        instance.setName("财税协同实例");
        instance.setVariables(Map.of(
                "artifactDeliveryType", "feishu_doc",
                "artifactDeliveryUrl", "https://feishu.cn/docx/doxcn-inline-doc",
                "artifactDeliveryDocumentId", "doxcn-inline-doc"
        ));

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-2");
        nodeExecution.setNodeLabel("飞书完成通知");

        service.sendWorkflowCompletedNotification(instance, nodeExecution, Map.of(
                "channelId", "channel-2",
                "title", "${workflowTemplateName} 已完成",
                "messageTemplate", "文档链接：${artifactDeliveryUrl}\n文档ID：${artifactDeliveryDocumentId}"
        ));

        ArgumentCaptor<NotificationDispatchRequest> requestCaptor = ArgumentCaptor.forClass(NotificationDispatchRequest.class);
        verify(notificationDispatchService).send(requestCaptor.capture());

        NotificationDispatchRequest request = requestCaptor.getValue();
        assertThat(request.getMessage().getTitle()).isEqualTo("SchemaPlexAI演示-财税协同闭环-闭环工作流 已完成");
        assertThat(request.getMessage().getContent()).contains("https://feishu.cn/docx/doxcn-inline-doc");
        assertThat(request.getMessage().getContent()).contains("doxcn-inline-doc");
        assertThat(request.getMessage().getPreviewUrl()).isEqualTo("https://feishu.cn/docx/doxcn-inline-doc");
    }

    @Test
    void shouldFallbackToNestedArtifactVariablesWhenTopLevelVariablesMissing() {
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        WorkflowTemplateMapper workflowTemplateMapper = mock(WorkflowTemplateMapper.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        NotificationDispatchService notificationDispatchService = mock(NotificationDispatchService.class);

        WorkflowNotificationService service = new WorkflowNotificationService(
                notificationChannelMapper,
                mock(MessageTemplateMapper.class),
                workflowTemplateMapper,
                specMapper,
                notificationDispatchService,
                mock(AgentExecutionMapper.class),
                mock(ToolExecutionLogMapper.class),
                mock(AgentTraceService.class)
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-3");
        channel.setName("飞书");
        channel.setChannelType("feishu");
        when(notificationChannelMapper.selectById("channel-3")).thenReturn(channel);

        WorkflowTemplate workflowTemplate = new WorkflowTemplate();
        workflowTemplate.setId("wf-template-3");
        workflowTemplate.setName("SchemaPlexAI演示-数字化定制交付-闭环工作流");
        when(workflowTemplateMapper.selectById("wf-template-3")).thenReturn(workflowTemplate);

        Spec spec = new Spec();
        spec.setId("spec-3");
        spec.setName("数字化定制交付");
        when(specMapper.selectById("spec-3")).thenReturn(spec);

        when(notificationDispatchService.send(any(NotificationDispatchRequest.class)))
                .thenReturn(NotificationSendResult.builder()
                        .recordId("record-3")
                        .responseSummary("ok")
                        .build());

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-3");
        instance.setTemplateId("wf-template-3");
        instance.setSpecId("spec-3");
        instance.setName("数字化定制实例");
        instance.setVariables(Map.of(
                "scene_delivery_team_output", Map.of(
                        "handoffPayload", Map.of(
                                "docRef", Map.of(
                                        "deliveryUrl", "https://feishu.cn/docx/doxcn-nested-doc",
                                        "deliveryDocumentId", "doxcn-nested-doc"
                                )
                        )
                )
        ));

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-exec-3");
        nodeExecution.setNodeLabel("飞书完成通知");

        service.sendWorkflowCompletedNotification(instance, nodeExecution, Map.of(
                "channelId", "channel-3",
                "title", "${workflowTemplateName} 已完成",
                "messageTemplate", "文档链接：${artifactDeliveryUrl}\n文档ID：${artifactDeliveryDocumentId}"
        ));

        ArgumentCaptor<NotificationDispatchRequest> requestCaptor = ArgumentCaptor.forClass(NotificationDispatchRequest.class);
        verify(notificationDispatchService).send(requestCaptor.capture());

        NotificationDispatchRequest request = requestCaptor.getValue();
        assertThat(request.getMessage().getTitle()).isEqualTo("SchemaPlexAI演示-数字化定制交付-闭环工作流 已完成");
        assertThat(request.getMessage().getContent()).contains("https://feishu.cn/docx/doxcn-nested-doc");
        assertThat(request.getMessage().getContent()).contains("doxcn-nested-doc");
        assertThat(request.getMessage().getPreviewUrl()).isEqualTo("https://feishu.cn/docx/doxcn-nested-doc");
    }

    @Test
    void shouldRenderCompactImageNotificationWithoutLongPromptOrSignedUrl() {
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        WorkflowTemplateMapper workflowTemplateMapper = mock(WorkflowTemplateMapper.class);
        NotificationDispatchService notificationDispatchService = mock(NotificationDispatchService.class);

        WorkflowNotificationService service = new WorkflowNotificationService(
                notificationChannelMapper,
                mock(MessageTemplateMapper.class),
                workflowTemplateMapper,
                mock(SpecMapper.class),
                notificationDispatchService,
                mock(AgentExecutionMapper.class),
                mock(ToolExecutionLogMapper.class),
                mock(AgentTraceService.class)
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-image");
        channel.setTenantId("tenant-1");
        channel.setName("飞书");
        channel.setChannelType("feishu");
        when(notificationChannelMapper.selectById("channel-image")).thenReturn(channel);

        WorkflowTemplate workflowTemplate = new WorkflowTemplate();
        workflowTemplate.setId("wf-image");
        workflowTemplate.setName("插画生成工作流");
        when(workflowTemplateMapper.selectById("wf-image")).thenReturn(workflowTemplate);

        String signedImageUrl = "https://ark-content-generation-v2-cn-beijing.tos-cn-beijing.volces.com/doubao-seedream-4-5/image_0.jpeg"
                + "?X-Tos-Algorithm=TOS4-HMAC-SHA256&X-Tos-Credential=very-long-signed-token&X-Tos-Signature=signature";
        String longPrompt = "请生成图片" + "上下文过长".repeat(300);
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-image");
        instance.setTenantId("tenant-1");
        instance.setTemplateId("wf-image");
        instance.setName("插画生成实例");
        instance.setVariables(Map.of(
                "specName", "短Prompt通知约束复测",
                "imageUrls", List.of(signedImageUrl),
                "illustrationResult", Map.of(
                        "modelName", "Doubao Seedream 4.5",
                        "size", "2K",
                        "prompt", longPrompt,
                        "imageUrls", List.of(signedImageUrl)
                )
        ));

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-image");
        nodeExecution.setNodeLabel("飞书完成通知");

        when(notificationDispatchService.send(any(NotificationDispatchRequest.class)))
                .thenReturn(NotificationSendResult.builder()
                        .recordId("record-image")
                        .responseSummary("ok")
                        .build());

        service.sendWorkflowCompletedNotification(instance, nodeExecution, Map.of(
                "channelId", "channel-image",
                "title", "${specName}",
                "messageTemplate", "需求：${specName}\n图片：${imageUrl}\n结果：${illustrationResult}"
        ));

        ArgumentCaptor<NotificationDispatchRequest> requestCaptor = ArgumentCaptor.forClass(NotificationDispatchRequest.class);
        verify(notificationDispatchService).send(requestCaptor.capture());

        NotificationDispatchRequest request = requestCaptor.getValue();
        String content = request.getMessage().getContent();
        assertThat(request.getTenantId()).isEqualTo("tenant-1");
        assertThat(request.getMessage().getTitle()).isEqualTo("短Prompt通知约束复测");
        assertThat(content).contains("短Prompt通知约束复测");
        assertThat(content).contains("https://ark-content-generation-v2-cn-beijing.tos-cn-beijing.volces.com/doubao-seedream-4-5/image_0.jpeg");
        assertThat(content).doesNotContain("X-Tos-Signature");
        assertThat(content).doesNotContain("X-Tos-Credential");
        assertThat(content).doesNotContain(longPrompt);
        assertThat(content.length()).isLessThan(900);
    }

    @Test
    void shouldAppendMarketingSummaryVariablesToNotification() {
        NotificationChannelMapper notificationChannelMapper = mock(NotificationChannelMapper.class);
        WorkflowTemplateMapper workflowTemplateMapper = mock(WorkflowTemplateMapper.class);
        NotificationDispatchService notificationDispatchService = mock(NotificationDispatchService.class);
        AgentExecutionMapper agentExecutionMapper = mock(AgentExecutionMapper.class);
        ToolExecutionLogMapper toolExecutionLogMapper = mock(ToolExecutionLogMapper.class);
        AgentTraceService agentTraceService = mock(AgentTraceService.class);

        WorkflowNotificationService service = new WorkflowNotificationService(
                notificationChannelMapper,
                mock(MessageTemplateMapper.class),
                workflowTemplateMapper,
                mock(SpecMapper.class),
                notificationDispatchService,
                agentExecutionMapper,
                toolExecutionLogMapper,
                agentTraceService
        );

        NotificationChannel channel = new NotificationChannel();
        channel.setId("channel-marketing");
        channel.setName("飞书");
        channel.setChannelType("feishu");
        when(notificationChannelMapper.selectById("channel-marketing")).thenReturn(channel);

        WorkflowTemplate workflowTemplate = new WorkflowTemplate();
        workflowTemplate.setId("wf-marketing");
        workflowTemplate.setName("SchemaPlexAI演示-财税协同闭环-闭环工作流");
        when(workflowTemplateMapper.selectById("wf-marketing")).thenReturn(workflowTemplate);

        AgentExecution execution = new AgentExecution();
        execution.setId("trace-1");
        execution.setTaskId("instance-marketing");
        execution.setConversationId("conv-1");
        execution.setAiModel("deepseek-v3.2");
        execution.setTokenInput(100L);
        execution.setTokenOutput(40L);
        when(agentExecutionMapper.selectList(any())).thenReturn(List.of(execution));

        ToolExecutionLog toolLog = new ToolExecutionLog();
        toolLog.setToolName("sys.read");
        toolLog.setErrorCode("PATH_NOT_FOUND");
        when(toolExecutionLogMapper.selectList(any())).thenReturn(List.of(toolLog));

        AgentTraceFailureCategoryVO category = new AgentTraceFailureCategoryVO();
        category.setCategory("tool");
        category.setCount(1L);
        AgentTraceFailureSummaryVO summary = new AgentTraceFailureSummaryVO();
        summary.setBlockingCount(1L);
        summary.setRecoverableCount(0L);
        summary.setCategories(List.of(category));
        when(agentTraceService.summarizeFailures("trace-1")).thenReturn(summary);

        when(notificationDispatchService.send(any(NotificationDispatchRequest.class)))
                .thenReturn(NotificationSendResult.builder().recordId("record-1").responseSummary("ok").build());

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-marketing");
        instance.setTemplateId("wf-marketing");
        instance.setName("财税协同实例");
        instance.setVariables(Map.of("scenarioCode", "14", "scenarioName", "财税协同闭环"));

        WorkflowNodeExecution nodeExecution = new WorkflowNodeExecution();
        nodeExecution.setId("node-marketing");
        nodeExecution.setNodeLabel("飞书完成通知");

        service.sendWorkflowCompletedNotification(instance, nodeExecution, Map.of(
                "channelId", "channel-marketing",
                "title", "${scenarioCode}-${artifactTitle}",
                "messageTemplate", "实例=${instanceId}\n模型=${modelName}\nToken=${totalTokens}\n失败=${failedToolSummary}\n摘要=${failureSummary}"
        ));

        ArgumentCaptor<NotificationDispatchRequest> requestCaptor = ArgumentCaptor.forClass(NotificationDispatchRequest.class);
        verify(notificationDispatchService).send(requestCaptor.capture());
        String content = requestCaptor.getValue().getMessage().getContent();
        assertThat(requestCaptor.getValue().getMessage().getTitle()).isEqualTo("14-财税协同闭环交付产物");
        assertThat(content).contains("instance-marketing");
        assertThat(content).contains("deepseek-v3.2");
        assertThat(content).contains("140");
        assertThat(content).contains("sys.read(PATH_NOT_FOUND)");
        assertThat(content).contains("阻断=1");
    }

}
