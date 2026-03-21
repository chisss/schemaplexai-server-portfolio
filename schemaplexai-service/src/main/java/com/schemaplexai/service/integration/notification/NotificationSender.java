package com.schemaplexai.service.integration.notification;

import java.util.Map;

public interface NotificationSender {

    /**
     * 渠道类型
     */
    String getChannelType();

    /**
     * 发送测试消息
     */
    String sendTestMessage(Map<String, Object> config) throws Exception;
}
