package com.schemaplexai.service.workflow;

import com.schemaplexai.model.dto.workflow.ReviewCommentCreateRequest;
import com.schemaplexai.model.dto.workflow.ReviewDecisionRequest;
import com.schemaplexai.model.dto.workflow.ReviewSessionCreateRequest;
import com.schemaplexai.model.vo.workflow.ReviewCommentVO;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;

import java.util.List;

/**
 * 评审会话服务接口
 */
public interface ReviewSessionService {

    ReviewSessionVO create(ReviewSessionCreateRequest request);

    ReviewCommentVO submitComment(String sessionId, ReviewCommentCreateRequest request);

    ReviewSessionVO getWithSummary(String sessionId);

    ReviewSessionVO getLatestByWorkflowNode(String workflowInstanceId, String workflowNodeId);

    List<ReviewSessionVO> getMyPending();

    ReviewSessionVO approve(String sessionId, ReviewDecisionRequest request);

    ReviewSessionVO reject(String sessionId, ReviewDecisionRequest request);

    ReviewSessionVO requestModify(String sessionId, ReviewDecisionRequest request);

    /**
     * 检查会话是否全部完成并更新状态
     */
    void checkAndCompleteSession(String sessionId);

    /**
     * 处理超时会话（定时任务调用）
     */
    void handleTimeoutSessions();
}
