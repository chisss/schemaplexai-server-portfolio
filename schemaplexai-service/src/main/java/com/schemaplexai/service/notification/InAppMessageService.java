package com.schemaplexai.service.notification;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.notification.InAppMessageQueryRequest;
import com.schemaplexai.model.vo.notification.InAppMessageVO;

import java.util.List;
import java.util.Map;

/**
 * 站内信服务
 */
public interface InAppMessageService {

    void createMessage(String tenantId,
                       String title,
                       String content,
                       String messageType,
                       String businessType,
                       String sourceType,
                       String sourceId,
                       String actionUrl,
                       Map<String, Object> payload,
                       List<String> recipientUserIds);

    PageResult<InAppMessageVO> pageInbox(InAppMessageQueryRequest request);

    long countUnread();

    void markRead(String recipientId);

    void archive(String recipientId);

    void archiveBySource(String sourceType, String sourceId);

    void archivePendingReviewMessages(String tenantId,
                                      String specId,
                                      String workflowNodeId,
                                      String excludeSourceId);
}
