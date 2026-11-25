package com.schemaplexai.service.agent.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.ChatMessageMapper;
import com.schemaplexai.model.dto.agent.AgentExecuteDTO;
import com.schemaplexai.model.dto.agent.AgentExecutionQueryDTO;
import com.schemaplexai.model.dto.agent.AgentExecutionInputDTO;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.model.entity.ChatMessageEntity;
import com.schemaplexai.model.vo.agent.ConversationMessageVO;
import com.schemaplexai.service.agent.execution.AgentExecutionEvent;
import com.schemaplexai.service.agent.execution.AgentLogService;
import com.schemaplexai.service.agent.execution.AgentTerminalEventOutbox;
import com.schemaplexai.service.agent.execution.ExecutionEventStreamService;
import com.schemaplexai.service.agent.runtime.AgentRuntimeOrchestrator;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.cost.BudgetAdmissionService;
import com.schemaplexai.service.desktop.DesktopAttachmentService;
import com.schemaplexai.service.desktop.DesktopCapabilityService;
import com.schemaplexai.service.desktop.DesktopWorkspaceService;
import com.schemaplexai.service.security.SecurityRuntimeGuardService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentDesktopVisibilityTest {

    @Mock private AgentExecutionMapper executionMapper;
    @Mock private AgentMapper agentMapper;
    @Mock private ChatMessageMapper messageMapper;
    @Mock private EntityValidator entityValidator;
    @Mock private DesktopAttachmentService desktopAttachments;
    @Mock private DesktopWorkspaceService desktopWorkspaceService;
    @Mock private DesktopCapabilityService desktopCapabilities;
    @Mock private BudgetAdmissionService budgetAdmissionService;
    @Mock private SecurityRuntimeGuardService securityRuntimeGuardService;
    @Mock private AgentRuntimeOrchestrator orchestrator;
    @Mock private AgentLogService agentLogService;
    @Mock private ExecutionEventStreamService stream;
    @Mock private AgentTerminalEventOutbox terminalOutbox;
    @InjectMocks private AgentServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), AgentExecution.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"), ChatMessageEntity.class);
        SecurityUtil.setCurrentTenantId("tenant");
        SecurityUtil.setCurrentUserId("reader");
    }

    @AfterEach
    void tearDown() {
        SecurityUtil.clear();
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldApplyDesktopOwnerConstraintInDatabaseBeforePagination() {
        when(executionMapper.selectPage(any(Page.class), any())).thenReturn(new Page<AgentExecution>(1, 10, 0));

        service.pageExecutions("agent", new AgentExecutionQueryDTO());

        ArgumentCaptor<LambdaQueryWrapper<AgentExecution>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(executionMapper).selectPage(any(Page.class), query.capture());
        String sql = query.getValue().getSqlSegment();
        assertThat(sql).contains("agent_id =", "AND ((input_context -> 'desktopDeviceId' IS NULL",
                "input_context -> 'desktopWorkspaceId' IS NULL", "NOT LIKE 'desktop-%'",
                "NOT LIKE '%:desktop-%') OR created_by =");
        assertThat(query.getValue().getParamNameValuePairs()).containsValues("agent", "reader");
    }

    @Test
    void shouldDenyDesktopConversationHistoryBeforeReadingChatMessages() {
        AgentExecution execution = desktopExecution();
        when(executionMapper.selectList(any())).thenReturn(List.of(execution));

        assertThatThrownBy(() -> service.getConversationHistory("conversation"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(messageMapper);
    }

    @Test
    void shouldDenyReusingOtherUsersDesktopConversationForNewExecution() {
        when(executionMapper.selectList(any())).thenReturn(List.of(desktopExecution()));
        AgentExecuteDTO request = new AgentExecuteDTO();
        request.setPrompt("继续这个任务");
        request.setConversationId("conversation");

        assertThatThrownBy(() -> service.execute("agent", request)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(entityValidator);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCaptureProjectOnlyFromTrustedDesktopConversation(boolean desktop) {
        var agent = new Agent();
        agent.setId("agent");
        agent.setStatus("active");
        var request = new AgentExecuteDTO();
        request.setPrompt("生成项目汇总");
        request.setConversationId(desktop ? "desktop-owned" : "ordinary-conversation");
        request.setContext(Map.of("projectId", "supplied-by-request"));
        if (desktop) {
            when(desktopWorkspaceService.conversation("desktop-owned")).thenReturn(
                    new DesktopWorkspaceService.Conversation("desktop-owned", "expert", "项目会话", "agent", "owned-project",
                            null, false, false, 1, null));
        }
        when(entityValidator.requireExists(agentMapper, "agent", ResultCode.AGENT_NOT_FOUND)).thenReturn(agent);
        when(executionMapper.insert(any(AgentExecution.class))).thenAnswer(invocation -> {
            AgentExecution execution = invocation.getArgument(0);
            execution.setId("execution-1");
            return 1;
        });

        assertThat(service.execute("agent", request).getStatus()).isEqualTo("queued");

        var execution = ArgumentCaptor.forClass(AgentExecution.class);
        verify(executionMapper).insert(execution.capture());
        assertThat(execution.getValue().getMemoryProjectId()).isEqualTo(desktop ? "owned-project" : null);
    }

    @Test
    void shouldRecordLaunchFailureForAlreadyPersistedExecution() {
        var agent = new Agent();
        agent.setId("agent");
        agent.setStatus("active");
        var conflict = new BusinessException(ResultCode.WORKSPACE_PATH_CONFLICT, "工作空间路径必须位于工作空间根目录内");
        when(entityValidator.requireExists(agentMapper, "agent", ResultCode.AGENT_NOT_FOUND)).thenReturn(agent);
        doAnswer(invocation -> {
            AgentExecution execution = invocation.getArgument(0);
            assertThat(execution.getStatus()).isEqualTo("queued");
            execution.setId("execution-1");
            return 1;
        }).when(executionMapper).insert(any(AgentExecution.class));
        when(orchestrator.execute(any())).thenThrow(conflict);
        when(agentLogService.tryTransitionExecutionStatus("execution-1", "queued", "failed",
                conflict.getMessage(), null, null, null)).thenReturn(true);

        var request = new AgentExecuteDTO();
        request.setPrompt("生成采购汇总");
        var result = service.execute("agent", request);

        assertThat(result.getExecutionId()).isEqualTo("execution-1");
        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getMessage()).isEqualTo(conflict.getMessage());
        var events = ArgumentCaptor.forClass(AgentExecutionEvent.class);
        verify(stream).publish(events.capture());
        assertThat(events.getAllValues()).extracting(AgentExecutionEvent::getEventType).containsExactly("QUEUED");
    }

    @Test
    void shouldPreserveExistingCloudConversationVisibility() {
        AgentExecution execution = new AgentExecution();
        execution.setCreatedBy("other-user");
        when(executionMapper.selectList(any())).thenReturn(List.of(execution));
        when(messageMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.getConversationHistory("conversation")).isEmpty();
        verify(messageMapper, times(2)).selectList(any());
    }

    @Test
    void shouldReadArchivedHistoryWithTheTenantPrefix() {
        when(messageMapper.selectList(any())).thenAnswer(invocation -> {
            LambdaQueryWrapper<ChatMessageEntity> query = invocation.getArgument(0);
            query.getSqlSegment();
            assertThat(query.getParamNameValuePairs()).containsValue("tenant:web-conversation");
            ChatMessageEntity message = new ChatMessageEntity();
            message.setTextContent("采购摘要已就绪。");
            return List.of(message);
        });

        assertThat(service.getConversationHistory("web-conversation"))
                .extracting(ConversationMessageVO::getTextContent)
                .containsExactly("采购摘要已就绪。");
        verify(messageMapper).selectList(any());
    }

    @Test
    void shouldFallBackToLegacyHistory() {
        ChatMessageEntity message = new ChatMessageEntity();
        message.setTextContent("历史回复");
        when(messageMapper.selectList(any())).thenReturn(List.of(), List.of(message));

        assertThat(service.getConversationHistory("web-conversation"))
                .extracting(ConversationMessageVO::getTextContent)
                .containsExactly("历史回复");
    }

    @Test
    void shouldRejectStaleApprovalForStoppedDesktopExecution() {
        AgentExecution execution = desktopExecution();
        execution.setCreatedBy("reader");
        execution.setStatus("stopped");
        when(executionMapper.selectOne(any())).thenReturn(execution);
        AgentExecutionInputDTO input = new AgentExecutionInputDTO();
        input.setApprovalDecision("approve");
        input.setApprovalId("old-approval");
        assertThatThrownBy(() -> service.submitExecutionInput("agent", "execution", input))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已结束");
        verifyNoInteractions(entityValidator);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldStopOnlyOwnedActiveTeamChildrenAndStoreCancellationInTransaction() {
        AgentExecution parent = ownedTeamExecution();
        AgentExecution stoppedChild = new AgentExecution();
        stoppedChild.setId("active-child");
        when(executionMapper.selectOne(any())).thenReturn(parent);
        when(executionMapper.update(any(AgentExecution.class), any())).thenReturn(1);
        when(executionMapper.selectList(any())).thenReturn(List.of(stoppedChild));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.stopExecution("agent", "team-execution");

            ArgumentCaptor<LambdaQueryWrapper<AgentExecution>> findChildren = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
            verify(executionMapper).selectList(findChildren.capture());
            assertThat(findChildren.getValue().getSqlSegment()).contains("tenant_id =", "agent_id =",
                    "parent_execution_id =", "created_by =", "status IN", "FOR UPDATE");
            assertThat(findChildren.getValue().getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                    "tenant", "agent", "team-execution", "reader", "queued", "running", "paused");
            ArgumentCaptor<LambdaUpdateWrapper<AgentExecution>> updates = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
            var stopped = ArgumentCaptor.forClass(AgentExecution.class);
            verify(executionMapper, times(2)).update(stopped.capture(), updates.capture());
            assertThat(updates.getValue().getSqlSegment()).contains("id IN");
            assertThat(updates.getValue().getParamNameValuePairs().values()).containsExactly("active-child");
            assertThat(stopped.getAllValues()).allSatisfy(execution -> {
                assertThat(execution.getStatus()).isEqualTo("stopped");
                assertThat(execution.getCompletedAt()).isNotNull();
            });
            verifyNoInteractions(stream);
            var events = ArgumentCaptor.forClass(AgentExecutionEvent.class);
            verify(terminalOutbox, times(2)).enqueue(events.capture());
            assertThat(events.getAllValues()).extracting(AgentExecutionEvent::getExecutionId)
                    .containsExactly("team-execution", "active-child");
            assertThat(events.getAllValues()).extracting(AgentExecutionEvent::getEventType)
                    .containsOnly("CANCELLED");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void shouldLeaveOtherOwnersTeamExecutionsUntouched() {
        AgentExecution parent = ownedTeamExecution();
        parent.setCreatedBy("other-user");
        when(executionMapper.selectOne(any())).thenReturn(parent);

        assertThatThrownBy(() -> service.stopExecution("agent", "team-execution"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("任务所属用户");
        verify(executionMapper, never()).selectList(any());
        verify(executionMapper, never()).update(any(AgentExecution.class), any());
        verifyNoInteractions(stream);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void shouldLockOnlyTheOwnedRunningTeamParentBeforeCreatingAMember() {
        AgentExecution child = ownedTeamExecution();
        child.setParentExecutionId("team-execution");
        var logs = new AgentLogService(null, executionMapper, mock(ApplicationEventPublisher.class), mock(AgentTerminalEventOutbox.class));
        when(executionMapper.selectOne(any())).thenReturn(null, ownedTeamExecution());
        when(executionMapper.insert(any(AgentExecution.class))).thenReturn(1);

        assertThat(logs.createTeamChildWhileParentRunning(child)).isFalse();
        verify(executionMapper, never()).insert(any(AgentExecution.class));
        assertThat(logs.createTeamChildWhileParentRunning(child)).isTrue();
        verify(executionMapper).insert(child);

        ArgumentCaptor<LambdaQueryWrapper<AgentExecution>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(executionMapper, times(2)).selectOne(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("id =", "tenant_id =", "agent_id =",
                "created_by =", "status =", "FOR SHARE");
        assertThat(query.getValue().getParamNameValuePairs().values()).containsExactlyInAnyOrder(
                "team-execution", "tenant", "agent", "reader", "running");
    }

    private AgentExecution ownedTeamExecution() {
        AgentExecution execution = desktopExecution();
        execution.setId("team-execution");
        execution.setAgentId("agent");
        execution.setTenantId("tenant");
        execution.setCreatedBy("reader");
        execution.setStatus("running");
        execution.setRuntimeEngine("team_langgraph4j");
        return execution;
    }

    private AgentExecution desktopExecution() {
        AgentExecution execution = new AgentExecution();
        execution.setCreatedBy("other-user");
        execution.setInputContext(Map.of("desktopDeviceId", "device", "desktopWorkspaceId", "workspace"));
        return execution;
    }
}
