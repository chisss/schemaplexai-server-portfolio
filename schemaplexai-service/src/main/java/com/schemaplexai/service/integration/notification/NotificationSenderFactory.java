package com.schemaplexai.service.integration.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息通知发送器工厂
 * 通过 Spring IoC 自动收集所有 NotificationSender 实现
 */
@Slf4j
@Component
public class NotificationSenderFactory {

    private final Map<String, NotificationSender> senderMap = new HashMap<>();

    public NotificationSenderFactory(List<NotificationSender> senders) {
        for (NotificationSender sender : senders) {
            senderMap.put(sender.getChannelType(), sender);
            log.info("注册消息通知发送器: type={}", sender.getChannelType());
        }
    }

    /**
     * 根据渠道类型获取对应的发送器
     */
    public NotificationSender getSender(String channelType) {
        NotificationSender sender = senderMap.get(channelType);
        if (sender == null) {
            throw new IllegalArgumentException("不支持的通知渠道类型: " + channelType);
        }
        return sender;
    }

    /**
     * 检查是否支持指定渠道类型
     */
    public boolean supports(String channelType) {
        return senderMap.containsKey(channelType);
    }
}
