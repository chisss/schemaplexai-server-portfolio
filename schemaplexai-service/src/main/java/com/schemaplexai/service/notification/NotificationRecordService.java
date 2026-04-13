package com.schemaplexai.service.notification;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.notification.NotificationRecordQueryRequest;
import com.schemaplexai.model.entity.NotificationRecord;
import com.schemaplexai.model.vo.notification.NotificationRecordVO;
import com.schemaplexai.service.integration.notification.model.NotificationDispatchRequest;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;

/**
 * 通知记录服务
 */
public interface NotificationRecordService {

    PageResult<NotificationRecordVO> page(NotificationRecordQueryRequest request);

    NotificationRecordVO getById(String id);

    NotificationRecord createSuccessRecord(NotificationDispatchRequest request, NotificationSendResult result);

    NotificationRecord createFailureRecord(NotificationDispatchRequest request, Exception exception);
}
