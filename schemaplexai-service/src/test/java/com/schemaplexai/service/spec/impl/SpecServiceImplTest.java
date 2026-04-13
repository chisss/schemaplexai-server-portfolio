package com.schemaplexai.service.spec.impl;

import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.SpecVersionMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.converter.SpecConverter;
import com.schemaplexai.model.converter.SpecDocumentConverter;
import com.schemaplexai.model.dto.spec.SpecCreateRequest;
import com.schemaplexai.model.dto.spec.SpecWorkflowStartRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.vo.spec.SpecWorkbenchVO;
import com.schemaplexai.model.vo.spec.SpecVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.service.artifact.ArtifactService;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.runtime.BuiltinQualityAssuranceService;
import com.schemaplexai.service.spec.handler.SpecVersionHandler;
import com.schemaplexai.service.spec.validator.SpecStatusValidator;
import com.schemaplexai.service.workflow.ReviewSessionService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import com.schemaplexai.service.workflow.runtime.SpecWorkflowRuntimeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpecServiceImplTest {

    @Test
    void shouldMapCompletedWorkflowToCompletedSpecStatus() throws Exception {
        SpecServiceImpl service = new SpecServiceImpl(
                mock(SpecMapper.class),
                mock(SpecDocumentMapper.class),
                mock(SpecVersionMapper.class),
                mock(SpecConverter.class),
                mock(SpecDocumentConverter.class),
                mock(SpecStatusValidator.class),
                mock(SpecVersionHandler.class),
                new EntityValidator(),
                mock(UserMapper.class),
                mock(WorkflowInstanceMapper.class),
                mock(WorkflowNodeExecutionMapper.class),
                mock(WorkflowTemplateMapper.class),
                mock(WorkflowInstanceService.class),
                mock(ReviewSessionService.class),
                mock(WorkflowNodeEngine.class),
                mock(SpecWorkflowRuntimeService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(RabbitTemplate.class),
                mock(ArtifactService.class)
        );
        WorkflowInstanceVO workflowInstance = new WorkflowInstanceVO();
        workflowInstance.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());

        Method method = SpecServiceImpl.class.getDeclaredMethod("resolveWorkflowDrivenSpecStatus", WorkflowInstanceVO.class);
        method.setAccessible(true);
        String status = (String) method.invoke(service, workflowInstance);

        assertThat(status).isEqualTo("completed");
    }

    @Test
    void shouldCreateWorkflowDraftWithoutStartingOnCreate() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        SpecConverter specConverter = mock(SpecConverter.class);
        SpecDocumentConverter specDocumentConverter = mock(SpecDocumentConverter.class);

        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setName("示例需求");
        spec.setWorkflowId("workflow-1");
        SpecVO specVO = new SpecVO();
        specVO.setId("spec-1");
        specVO.setOwner("");
        specVO.setWorkflowId("workflow-1");
        WorkflowInstanceVO workflowInstanceVO = new WorkflowInstanceVO();
        workflowInstanceVO.setId("wf-1");
        workflowInstanceVO.setStatus("pending");
        workflowInstanceVO.setDefinition(Map.of("nodes", List.of()));
        WorkflowInstance workflowInstance = new WorkflowInstance();
        workflowInstance.setId("wf-1");
        workflowInstance.setSpecId("spec-1");
        workflowInstance.setDefinition(Map.of("nodes", List.of()));

        when(specConverter.fromCreateRequest(any(SpecCreateRequest.class))).thenReturn(spec);
        when(specConverter.toVO(spec)).thenReturn(specVO);
        when(specMapper.selectById("spec-1")).thenReturn(spec);
        when(specVersionHandler.getAllDocuments("spec-1")).thenReturn(List.of());
        when(specDocumentConverter.toVOList(List.of())).thenReturn(List.of());

        WorkflowInstanceService workflowInstanceService = mock(WorkflowInstanceService.class);
        when(workflowInstanceService.create(any())).thenReturn(workflowInstanceVO);
        when(workflowInstanceService.getById("wf-1")).thenReturn(workflowInstanceVO);
        WorkflowInstanceMapper workflowInstanceMapper = mock(WorkflowInstanceMapper.class);
        when(workflowInstanceMapper.selectById("wf-1")).thenReturn(workflowInstance);

        SpecServiceImpl service = new SpecServiceImpl(
                specMapper,
                mock(SpecDocumentMapper.class),
                mock(SpecVersionMapper.class),
                specConverter,
                specDocumentConverter,
                mock(SpecStatusValidator.class),
                specVersionHandler,
                new EntityValidator(),
                mock(UserMapper.class),
                workflowInstanceMapper,
                mock(WorkflowNodeExecutionMapper.class),
                mock(WorkflowTemplateMapper.class),
                workflowInstanceService,
                mock(ReviewSessionService.class),
                mock(WorkflowNodeEngine.class),
                mock(SpecWorkflowRuntimeService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(RabbitTemplate.class),
                mock(ArtifactService.class)
        );

        SpecCreateRequest request = new SpecCreateRequest();
        request.setName("示例需求");
        request.setDescription("这里是手工创建时录入的初始需求");
        request.setWorkflowId("workflow-1");

        service.createSpec(request);

        verify(workflowInstanceService).create(any());
        verify(workflowInstanceService, never()).start("wf-1");
        verify(specVersionHandler, never()).saveDocument(eq("spec-1"), eq("requirements"), any(), any());
    }

    @Test
    void shouldNotClearWorkspaceIdsWhenSubmittingForReview() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        BuiltinQualityAssuranceService builtinQualityAssuranceService = mock(BuiltinQualityAssuranceService.class);
        SpecStatusValidator specStatusValidator = mock(SpecStatusValidator.class);

        Spec spec = new Spec();
        spec.setStatus("draft");
        spec.setWorkspaceIds(List.of("workspace-1"));
        when(specMapper.selectById("spec-1")).thenReturn(spec);
        doNothing().when(specVersionHandler).requireDocumentExists("spec-1", "requirements");
        when(builtinQualityAssuranceService.analyzeIntentDefects("spec-1", "requirements"))
                .thenReturn(Map.of("qualitySummary", "ok"));
        when(specStatusValidator.resolveReviewStatus("requirements")).thenReturn("requirements_review");
        doNothing().when(specStatusValidator).validateTransition("draft", "requirements_review");

        SpecServiceImpl service = new SpecServiceImpl(
                specMapper,
                mock(SpecDocumentMapper.class),
                mock(SpecVersionMapper.class),
                mock(SpecConverter.class),
                mock(SpecDocumentConverter.class),
                specStatusValidator,
                specVersionHandler,
                new EntityValidator(),
                mock(UserMapper.class),
                mock(WorkflowInstanceMapper.class),
                mock(WorkflowNodeExecutionMapper.class),
                mock(WorkflowTemplateMapper.class),
                mock(WorkflowInstanceService.class),
                mock(ReviewSessionService.class),
                mock(WorkflowNodeEngine.class),
                mock(SpecWorkflowRuntimeService.class),
                builtinQualityAssuranceService,
                mock(RabbitTemplate.class),
                mock(ArtifactService.class)
        );

        service.submitForReview("spec-1", "requirements");

        ArgumentCaptor<Spec> updateCaptor = ArgumentCaptor.forClass(Spec.class);
        verify(specMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getStatus()).isEqualTo("requirements_review");
        assertThat(updateCaptor.getValue().getWorkspaceIds()).isNull();
    }

    @Test
    void shouldMergeRuntimeVariablesWhenStartingWorkflowFromWorkbench() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        SpecConverter specConverter = mock(SpecConverter.class);
        SpecDocumentConverter specDocumentConverter = mock(SpecDocumentConverter.class);
        WorkflowInstanceMapper workflowInstanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        WorkflowInstanceService workflowInstanceService = mock(WorkflowInstanceService.class);
        SpecWorkflowRuntimeService specWorkflowRuntimeService = mock(SpecWorkflowRuntimeService.class);

        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setName("示例需求");
        spec.setWorkflowId("workflow-1");
        spec.setWorkflowInstanceId("wf-1");
        spec.setLifecycleMode("workflow");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setSpecId("spec-1");
        instance.setStatus("pending");
        instance.setVariables(Map.of("triggerType", "spec-workbench"));
        when(workflowInstanceMapper.selectById("wf-1")).thenReturn(instance);
        when(nodeExecutionMapper.selectCount(any())).thenReturn(0L);
        when(specWorkflowRuntimeService.buildRuntimeVariables(any(), any()))
                .thenReturn(Map.of(
                        "workspaceName", "titanium-policy",
                        "workspacePath", "/tmp/titanium-policy",
                        "artifactOutputPath", "docs/SPAI-TEST-1-technical-design.md"
                ));

        WorkflowInstanceVO workflowInstanceVO = new WorkflowInstanceVO();
        workflowInstanceVO.setId("wf-1");
        workflowInstanceVO.setStatus("running");
        workflowInstanceVO.setCurrentNodeId("start");
        workflowInstanceVO.setDefinition(Map.of("nodes", List.of()));
        when(workflowInstanceService.start("wf-1")).thenReturn(workflowInstanceVO);

        SpecVO specVO = new SpecVO();
        specVO.setId("spec-1");
        specVO.setOwner("");
        specVO.setWorkflowId("workflow-1");
        when(specConverter.toVO(spec)).thenReturn(specVO);
        when(specVersionHandler.getAllDocuments("spec-1")).thenReturn(List.of());
        when(specDocumentConverter.toVOList(List.of())).thenReturn(List.of());

        SpecServiceImpl service = new SpecServiceImpl(
                specMapper,
                mock(SpecDocumentMapper.class),
                mock(SpecVersionMapper.class),
                specConverter,
                specDocumentConverter,
                mock(SpecStatusValidator.class),
                specVersionHandler,
                new EntityValidator(),
                mock(UserMapper.class),
                workflowInstanceMapper,
                nodeExecutionMapper,
                mock(WorkflowTemplateMapper.class),
                workflowInstanceService,
                mock(ReviewSessionService.class),
                mock(WorkflowNodeEngine.class),
                specWorkflowRuntimeService,
                mock(BuiltinQualityAssuranceService.class),
                mock(RabbitTemplate.class),
                mock(ArtifactService.class)
        );

        SpecWorkflowStartRequest request = new SpecWorkflowStartRequest();
        request.setDescription("请为 titanium-policy 工作区生成真实需求分析");

        SecurityUtil.setCurrentUserId("user-1");
        SecurityUtil.setCurrentTenantId("tenant-1");
        try {
            service.startWorkflow("spec-1", request);
        } finally {
            SecurityUtil.clear();
        }

        ArgumentCaptor<WorkflowInstance> updateCaptor = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(workflowInstanceMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getVariables())
                .containsEntry("workspaceName", "titanium-policy")
                .containsEntry("workspacePath", "/tmp/titanium-policy")
                .containsEntry("artifactOutputPath", "docs/SPAI-TEST-1-technical-design.md")
                .containsEntry("originalRequirement", "请为 titanium-policy 工作区生成真实需求分析");
    }

    @Test
    void shouldHideStaleAgentDocumentWhenLatestExecutionIsStillRunning() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        SpecConverter specConverter = mock(SpecConverter.class);
        SpecDocumentConverter specDocumentConverter = mock(SpecDocumentConverter.class);
        WorkflowInstanceMapper workflowInstanceMapper = mock(WorkflowInstanceMapper.class);
        WorkflowNodeExecutionMapper nodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        WorkflowInstanceService workflowInstanceService = mock(WorkflowInstanceService.class);

        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setWorkflowInstanceId("wf-1");
        spec.setWorkflowId("");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("wf-1");
        instance.setSpecId("spec-1");
        instance.setStatus("running");
        instance.setVariables(Map.of("triggerType", "spec-workbench", "originalRequirement", "已有原始需求"));
        instance.setDefinition(Map.of(
                "nodes", List.of(Map.of(
                        "id", "task_breakdown",
                        "type", "agent",
                        "label", "任务拆分",
                        "config", Map.of("artifactDocType", "tasks")
                ))
        ));
        when(workflowInstanceMapper.selectById("wf-1")).thenReturn(instance);

        WorkflowInstanceVO workflowInstanceVO = new WorkflowInstanceVO();
        workflowInstanceVO.setId("wf-1");
        workflowInstanceVO.setStatus("running");
        workflowInstanceVO.setCurrentNodeId("task_breakdown");
        workflowInstanceVO.setDefinition(instance.getDefinition());
        when(workflowInstanceService.getById("wf-1")).thenReturn(workflowInstanceVO);

        WorkflowNodeExecution execution = new WorkflowNodeExecution();
        execution.setId("exec-1");
        execution.setInstanceId("wf-1");
        execution.setNodeId("task_breakdown");
        execution.setNodeType("agent");
        execution.setStatus("running");
        execution.setStartedAt(LocalDateTime.of(2026, 4, 6, 4, 0, 0));
        execution.setOutputData(Map.of("result", "最新任务拆分结果"));
        when(nodeExecutionMapper.selectByInstanceId("wf-1")).thenReturn(List.of(execution));

        SpecDocument staleDocument = new SpecDocument();
        staleDocument.setId("doc-1");
        staleDocument.setSpecId("spec-1");
        staleDocument.setDocType("tasks");
        staleDocument.setWorkflowNodeId("task_breakdown");
        staleDocument.setUpdatedAt(LocalDateTime.of(2026, 4, 6, 3, 59, 0));
        when(specVersionHandler.getDocumentByNode("spec-1", "task_breakdown")).thenReturn(staleDocument);
        when(specVersionHandler.getAllDocuments("spec-1")).thenReturn(List.of());
        when(specDocumentConverter.toVOList(List.of())).thenReturn(List.of());

        SpecVO specVO = new SpecVO();
        specVO.setId("spec-1");
        specVO.setOwner("");
        specVO.setWorkflowId("");
        when(specConverter.toVO(spec)).thenReturn(specVO);

        SpecServiceImpl service = new SpecServiceImpl(
                specMapper,
                mock(SpecDocumentMapper.class),
                mock(SpecVersionMapper.class),
                specConverter,
                specDocumentConverter,
                mock(SpecStatusValidator.class),
                specVersionHandler,
                new EntityValidator(),
                mock(UserMapper.class),
                workflowInstanceMapper,
                nodeExecutionMapper,
                mock(WorkflowTemplateMapper.class),
                workflowInstanceService,
                mock(ReviewSessionService.class),
                mock(WorkflowNodeEngine.class),
                mock(SpecWorkflowRuntimeService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(RabbitTemplate.class),
                mock(ArtifactService.class)
        );

        SpecWorkbenchVO workbench = service.getWorkbench("spec-1");

        assertThat(workbench.getNodes()).hasSize(1);
        assertThat(workbench.getNodes().get(0).getDocument()).isNull();
        assertThat(workbench.getNodes().get(0).getOutputData())
                .containsEntry("result", "最新任务拆分结果");
        verify(specVersionHandler, never()).getDocument("spec-1", "tasks");
    }

    @Test
    void shouldExposeCreatorDisplayNameOnSpecDetail() {
        SpecMapper specMapper = mock(SpecMapper.class);
        SpecVersionHandler specVersionHandler = mock(SpecVersionHandler.class);
        SpecConverter specConverter = mock(SpecConverter.class);
        SpecDocumentConverter specDocumentConverter = mock(SpecDocumentConverter.class);
        UserMapper userMapper = mock(UserMapper.class);

        Spec spec = new Spec();
        spec.setId("spec-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        SpecVO specVO = new SpecVO();
        specVO.setId("spec-1");
        specVO.setOwner("owner-1");
        specVO.setCreatedBy("creator-1");
        when(specConverter.toVO(spec)).thenReturn(specVO);
        when(specVersionHandler.getAllDocuments("spec-1")).thenReturn(List.of());
        when(specDocumentConverter.toVOList(List.of())).thenReturn(List.of());

        User owner = new User();
        owner.setId("owner-1");
        owner.setRealName("负责人");
        User creator = new User();
        creator.setId("creator-1");
        creator.setUsername("creator");
        creator.setRealName("创建人甲");
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(owner, creator));

        SpecServiceImpl service = new SpecServiceImpl(
                specMapper,
                mock(SpecDocumentMapper.class),
                mock(SpecVersionMapper.class),
                specConverter,
                specDocumentConverter,
                mock(SpecStatusValidator.class),
                specVersionHandler,
                new EntityValidator(),
                userMapper,
                mock(WorkflowInstanceMapper.class),
                mock(WorkflowNodeExecutionMapper.class),
                mock(WorkflowTemplateMapper.class),
                mock(WorkflowInstanceService.class),
                mock(ReviewSessionService.class),
                mock(WorkflowNodeEngine.class),
                mock(SpecWorkflowRuntimeService.class),
                mock(BuiltinQualityAssuranceService.class),
                mock(RabbitTemplate.class),
                mock(ArtifactService.class)
        );

        SpecVO result = service.getSpecById("spec-1");

        assertThat(result.getOwnerName()).isEqualTo("负责人");
        assertThat(result.getCreatedByName()).isEqualTo("创建人甲");
    }
}
