package com.schemaplexai.service.workflow.impl;

import com.schemaplexai.dao.mapper.ReviewCommentMapper;
import com.schemaplexai.dao.mapper.ReviewSessionMapper;
import com.schemaplexai.dao.mapper.RoleMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.UserRoleMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.model.converter.ReviewCommentConverter;
import com.schemaplexai.model.converter.ReviewSessionConverter;
import com.schemaplexai.model.dto.workflow.ReviewSessionCreateRequest;
import com.schemaplexai.model.entity.ReviewComment;
import com.schemaplexai.model.entity.ReviewSession;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.vo.workflow.ReviewCommentVO;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import com.schemaplexai.service.notification.InAppMessageService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.runtime.ReviewNotificationHelper;
import com.schemaplexai.service.workflow.validator.ReviewValidator;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewSessionServiceImplTest {

    @Test
    @SuppressWarnings("unchecked")
    void shouldPersistReviewerNameSnapshotWhenCreateReviewSession() {
        ReviewSessionMapper sessionMapper = mock(ReviewSessionMapper.class);
        ReviewCommentMapper commentMapper = mock(ReviewCommentMapper.class);
        ReviewSessionConverter sessionConverter = mock(ReviewSessionConverter.class);
        ReviewCommentConverter commentConverter = mock(ReviewCommentConverter.class);
        ReviewValidator reviewValidator = mock(ReviewValidator.class);
        WorkflowInstanceService workflowInstanceService = mock(WorkflowInstanceService.class);
        InAppMessageService inAppMessageService = mock(InAppMessageService.class);
        SpecMapper specMapper = mock(SpecMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        WorkflowNodeExecutionMapper workflowNodeExecutionMapper = mock(WorkflowNodeExecutionMapper.class);
        ReviewNotificationHelper reviewNotificationHelper = mock(ReviewNotificationHelper.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        RoleMapper roleMapper = mock(RoleMapper.class);
        UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);

        ReviewSessionServiceImpl service = new ReviewSessionServiceImpl(
                sessionMapper,
                commentMapper,
                sessionConverter,
                commentConverter,
                reviewValidator,
                workflowInstanceService,
                inAppMessageService,
                specMapper,
                userMapper,
                workflowNodeExecutionMapper,
                reviewNotificationHelper,
                rabbitTemplate,
                roleMapper,
                userRoleMapper
        );

        when(userMapper.selectBatchIds(any())).thenReturn(List.of(buildUser("reviewer-1", "架构师A")));
        when(sessionMapper.insert(org.mockito.ArgumentMatchers.<ReviewSession>any())).thenAnswer(invocation -> {
            ReviewSession session = invocation.getArgument(0);
            session.setId("session-1");
            return 1;
        });
        when(sessionConverter.toVO(any())).thenAnswer(invocation -> {
            ReviewSession session = invocation.getArgument(0);
            ReviewSessionVO vo = new ReviewSessionVO();
            vo.setId(session.getId());
            vo.setReviewers(session.getReviewers().stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList());
            return vo;
        });
        when(commentMapper.selectList(any())).thenReturn(List.<ReviewComment>of());
        when(commentConverter.toVOList(any())).thenReturn(List.<ReviewCommentVO>of());

        ReviewSessionCreateRequest request = new ReviewSessionCreateRequest();
        request.setSpecId("spec-1");
        request.setTenantId("tenant-1");
        request.setOwner("owner-1");
        request.setWorkflowInstanceId("wf-1");
        request.setWorkflowNodeId("review-1");
        request.setDocumentType("design");
        request.setReviewers(List.of(Map.of(
                "userId", "reviewer-1",
                "role", "架构评审"
        )));

        ReviewSessionVO result = service.create(request);

        ArgumentCaptor<ReviewSession> captor = ArgumentCaptor.forClass(ReviewSession.class);
        verify(sessionMapper).insert(captor.capture());
        Map<String, Object> reviewerSnapshot = (Map<String, Object>) captor.getValue().getReviewers().get(0);

        assertThat(reviewerSnapshot.get("userId")).isEqualTo("reviewer-1");
        assertThat(reviewerSnapshot.get("reviewerName")).isEqualTo("架构师A");
        assertThat(reviewerSnapshot.get("role")).isEqualTo("架构评审");
        assertThat(reviewerSnapshot.get("status")).isEqualTo("pending");
        assertThat(result.getReviewers()).hasSize(1);
        assertThat(result.getReviewers().get(0).get("reviewerName")).isEqualTo("架构师A");
    }

    private User buildUser(String id, String realName) {
        User user = new User();
        user.setId(id);
        user.setRealName(realName);
        user.setUsername(realName);
        return user;
    }
}
