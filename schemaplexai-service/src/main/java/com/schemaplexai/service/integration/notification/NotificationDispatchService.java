package com.schemaplexai.service.integration.notification;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.NotificationRecord;
import com.schemaplexai.service.integration.notification.model.NotificationDispatchRequest;
import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import com.schemaplexai.service.notification.NotificationRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 通知消息分发服务
 */
@Service
@RequiredArgsConstructor
public class NotificationDispatchService {

    private final NotificationSenderFactory notificationSenderFactory;
    private final NotificationRecordService notificationRecordService;

    public NotificationSendResult send(NotificationDispatchRequest request) {
        NotificationChannel channel = request != null ? request.getChannel() : null;
        NotificationMessage message = request != null ? request.getMessage() : null;
        if (channel == null) {
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);
        }
        try {
            NotificationSender sender = notificationSenderFactory.getSender(channel.getChannelType());
            NotificationSendResult result = sender.sendMessage(channel.getConfig(), message);
            NotificationRecord record = notificationRecordService.createSuccessRecord(request, result);
            result.setRecordId(record.getId());
            return result;
        } catch (BusinessException ex) {
            notificationRecordService.createFailureRecord(request, ex);
            throw ex;
        } catch (Exception ex) {
            notificationRecordService.createFailureRecord(request, ex);
            throw new BusinessException(ResultCode.NOTIFICATION_CHANNEL_SEND_FAILED, ex.getMessage());
        }
    }
}
