package com.schemaplexai.service.channel.validator;

import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.service.common.EntityValidator;
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

    /**
     * 校验渠道名称唯一性
     */
    public void validateNameUnique(String name) {
        entityValidator.checkUnique(channelMapper, NotificationChannel::getName, name,
                ResultCode.NOTIFICATION_CHANNEL_NAME_DUPLICATE);
    }
}
