package com.schemaplexai.service.channel.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.NotificationChannelStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.NotificationChannelMapper;
import com.schemaplexai.model.converter.NotificationChannelConverter;
import com.schemaplexai.model.dto.channel.NotificationChannelCreateRequest;
import com.schemaplexai.model.dto.channel.NotificationChannelQueryRequest;
import com.schemaplexai.model.dto.channel.NotificationChannelUpdateRequest;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.vo.channel.NotificationChannelVO;
import com.schemaplexai.service.channel.NotificationChannelService;
import com.schemaplexai.service.channel.validator.NotificationChannelValidator;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.notification.NotificationSender;
import com.schemaplexai.service.integration.notification.NotificationSenderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 通知渠道服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationChannelServiceImpl implements NotificationChannelService {

    private final NotificationChannelMapper channelMapper;
    private final NotificationChannelConverter channelConverter;
    private final NotificationChannelValidator channelValidator;
    private final EntityValidator entityValidator;
    private final NotificationSenderFactory notificationSenderFactory;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationChannelVO create(NotificationChannelCreateRequest request) {
        channelValidator.validateNameUnique(request.getName());

        var channel = channelConverter.fromCreateRequest(request);
        channelMapper.insert(channel);
        log.info("创建通知渠道成功: channelId={}, name={}, type={}",
                channel.getId(), channel.getName(), channel.getChannelType());

        var vo = channelConverter.toVO(channel);
        vo.setConfig(maskConfig(channel.getConfig()));
        return vo;
    }

    @Override
    public PageResult<NotificationChannelVO> page(NotificationChannelQueryRequest request) {
        var page = new Page<NotificationChannel>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<NotificationChannel>();

        if (StringUtils.hasText(request.getChannelType())) {
            wrapper.eq(NotificationChannel::getChannelType, request.getChannelType());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(NotificationChannel::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(NotificationChannel::getName, request.getKeyword())
                    .or().like(NotificationChannel::getDescription, request.getKeyword()));
        }
        wrapper.orderByDesc(NotificationChannel::getCreatedAt);

        var result = channelMapper.selectPage(page, wrapper);
        var voList = channelConverter.toVOList(result.getRecords());
        for (NotificationChannelVO vo : voList) {
            vo.setConfig(maskConfig(vo.getConfig()));
        }
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public NotificationChannelVO getById(String id) {
        var channel = entityValidator.requireExists(channelMapper, id, ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);
        var vo = channelConverter.toVO(channel);
        vo.setConfig(maskConfig(channel.getConfig()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationChannelVO update(String id, NotificationChannelUpdateRequest request) {
        var channel = entityValidator.requireExists(channelMapper, id, ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);

        if (StringUtils.hasText(request.getName())) {
            channel.setName(request.getName());
        }
        if (request.getConfig() != null) {
            channel.setConfig(request.getConfig());
        }
        if (StringUtils.hasText(request.getStatus())) {
            channel.setStatus(request.getStatus());
        }
        if (request.getDescription() != null) {
            channel.setDescription(request.getDescription());
        }
        if (request.getNotificationRules() != null) {
            channel.setNotificationRules(request.getNotificationRules());
        }

        channelMapper.updateById(channel);
        log.info("更新通知渠道成功: channelId={}", id);

        var vo = channelConverter.toVO(channel);
        vo.setConfig(maskConfig(channel.getConfig()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(channelMapper, id, ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);
        channelMapper.deleteById(id);
        log.info("删除通知渠道成功: channelId={}", id);
    }

    @Override
    public NotificationChannelVO testChannel(String id) {
        var channel = entityValidator.requireExists(channelMapper, id, ResultCode.NOTIFICATION_CHANNEL_NOT_FOUND);

        // 根据渠道类型获取对应的发送器，发送测试消息
        try {
            NotificationSender sender = notificationSenderFactory.getSender(channel.getChannelType());
            String result = sender.sendTestMessage(channel.getConfig());
            log.info("通知渠道测试成功: channelId={}, type={}, result={}", id, channel.getChannelType(), result);

            channel.setLastTestAt(LocalDateTime.now());
            channel.setStatus(CommonConstant.STATUS_ACTIVE);
            channel.setErrorMessage(null);
        } catch (Exception e) {
            log.error("通知渠道测试失败: channelId={}, type={}", id, channel.getChannelType(), e);
            channel.setLastTestAt(LocalDateTime.now());
            channel.setStatus(NotificationChannelStatusEnum.ERROR.getCode());
            channel.setErrorMessage("测试发送失败: " + e.getMessage());
        }

        channelMapper.updateById(channel);

        var vo = channelConverter.toVO(channel);
        vo.setConfig(maskConfig(channel.getConfig()));
        return vo;
    }

    /**
     * 配置信息脱敏
     */
    private Map<String, Object> maskConfig(Map<String, Object> config) {
        if (config == null) {
            return null;
        }
        var masked = new HashMap<String, Object>(config.size());
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String lowerKey = entry.getKey().toLowerCase();
            if (lowerKey.contains("secret") || lowerKey.contains("token")
                    || lowerKey.contains("password") || lowerKey.contains("api_key")
                    || lowerKey.contains("apikey") || lowerKey.contains("app_secret")
                    || lowerKey.contains("private_key")) {
                masked.put(entry.getKey(), "***");
            } else {
                masked.put(entry.getKey(), entry.getValue());
            }
        }
        return masked;
    }
}
