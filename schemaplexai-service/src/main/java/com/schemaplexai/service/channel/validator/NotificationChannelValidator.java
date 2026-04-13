package com.schemaplexai.service.channel.validator;

import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.notification.NotificationSenderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 通知渠道业务校验器
 */
@Component
@RequiredArgsConstructor
public class NotificationChannelValidator {

    private final NotificationChannelMapper channelMapper;
    private final EntityValidator entityValidator;
    private final NotificationSenderFactory notificationSenderFactory;

    /**
     * 校验渠道名称唯一性
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(channelMapper, NotificationChannel::getName, name,
                ResultCode.NOTIFICATION_CHANNEL_NAME_DUPLICATE);
    }

    public void validateSupportedType(String channelType) {
        if (!notificationSenderFactory.supports(channelType)) {
            throw new BusinessException(ResultCode.INTEGRATION_CONFIG_INVALID, "不支持的通知渠道类型: " + channelType);
        }
    }
}
