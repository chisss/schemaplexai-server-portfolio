package com.schemaplexai.service.quality.feedback;

import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.enums.GateDecisionEnum;
import com.schemaplexai.dao.mapper.QualityIssueMapper;
import com.schemaplexai.model.entity.QualityIssue;
import com.schemaplexai.service.notification.InAppMessageService;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QualityIssueFeedbackServiceImplTest {

    @Test
    void shouldResumePausedWorkflowBeforeMarkingIssueResolved() {
        QualityIssueMapper qualityIssueMapper = mock(QualityIssueMapper.class);
        InAppMessageService inAppMessageService = mock(InAppMessageService.class);
        WorkflowNodeEngine workflowNodeEngine = mock(WorkflowNodeEngine.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<WorkflowNodeEngine> engineProvider = mock(ObjectProvider.class);
        when(engineProvider.getObject()).thenReturn(workflowNodeEngine);

        QualityIssueFeedbackServiceImpl service = new QualityIssueFeedbackServiceImpl(
                qualityIssueMapper,
                inAppMessageService,
                engineProvider
        );

        QualityIssue issue = new QualityIssue();
        issue.setId("issue-1");
        issue.setGateDecision(GateDecisionEnum.PAUSE.getCode());
        issue.setWorkflowInstanceId("wf-1");
        issue.setWorkflowNodeId("code_development");
        issue.setStatus(DeviationStatusEnum.OPEN.getCode());
        when(qualityIssueMapper.selectById("issue-1")).thenReturn(issue);

        service.resumeBlockedWorkflow("issue-1", "user-1");

        verify(workflowNodeEngine).resumePausedWorkflow(
                eq("wf-1"),
                eq("code_development"),
                argThat(payload -> Boolean.TRUE.equals(payload.get("qualityOverride"))
                        && "issue-1".equals(payload.get("qualityIssueId"))
                        && "user-1".equals(payload.get("overrideBy")))
        );
        verify(qualityIssueMapper).updateById(argThat((QualityIssue updated) -> {
            assertThat(updated.getStatus()).isEqualTo(DeviationStatusEnum.RESOLVED.getCode());
            assertThat(updated.getResolutionAction()).isEqualTo("override");
            assertThat(updated.getResolvedBy()).isEqualTo("user-1");
            return true;
        }));
    }
}
