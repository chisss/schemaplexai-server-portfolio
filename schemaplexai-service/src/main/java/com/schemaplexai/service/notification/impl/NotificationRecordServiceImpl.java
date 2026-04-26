package com.schemaplexai.service.notification.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.NotificationRecordMapper;
import com.schemaplexai.model.converter.NotificationRecordConverter;
import com.schemaplexai.model.dto.notification.NotificationRecordQueryRequest;
import com.schemaplexai.model.entity.NotificationChannel;
import com.schemaplexai.model.entity.NotificationRecord;
import com.schemaplexai.model.vo.notification.NotificationRecordVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.notification.model.NotificationDispatchRequest;
import com.schemaplexai.service.integration.notification.model.NotificationMessage;
import com.schemaplexai.service.integration.notification.model.NotificationSendResult;
import com.schemaplexai.service.notification.NotificationRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 通知记录服务实现
 */
@Service
@RequiredArgsConstructor
public class NotificationRecordServiceImpl implements NotificationRecordService {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    );

    private final NotificationRecordMapper notificationRecordMapper;
    private final NotificationRecordConverter notificationRecordConverter;
    private final EntityValidator entityValidator;

    @Override
    public PageResult<NotificationRecordVO> page(NotificationRecordQueryRequest request) {
        Page<NotificationRecord> page = new Page<>(request.getPage(), request.getSize());
        LambdaQueryWrapper<NotificationRecord> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(request.getChannelType())) {
            wrapper.eq(NotificationRecord::getChannelType, request.getChannelType());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(NotificationRecord::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getTemplateType())) {
            wrapper.eq(NotificationRecord::getTemplateType, request.getTemplateType());
        }
        if (StringUtils.hasText(request.getSourceType())) {
            wrapper.eq(NotificationRecord::getSourceType, request.getSourceType());
        }
        if (request.getSentAtStart() != null) {
            wrapper.ge(NotificationRecord::getSentAt, request.getSentAtStart());
        }
        if (request.getSentAtEnd() != null) {
            wrapper.le(NotificationRecord::getSentAt, request.getSentAtEnd());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(q -> {
                q.like(NotificationRecord::getChannelName, request.getKeyword())
                        .or().like(NotificationRecord::getTemplateName, request.getKeyword())
                        .or().like(NotificationRecord::getTitle, request.getKeyword())
                        .or().like(NotificationRecord::getContent, request.getKeyword())
                        .or().like(NotificationRecord::getSourceId, request.getKeyword());
                if (isUuidKeyword(request.getKeyword())) {
                    q.or().eq(NotificationRecord::getWorkflowInstanceId, request.getKeyword())
                            .or().eq(NotificationRecord::getWorkflowNodeExecutionId, request.getKeyword())
                            .or().eq(NotificationRecord::getSpecId, request.getKeyword())
                            .or().eq(NotificationRecord::getTemplateId, request.getKeyword())
                            .or().eq(NotificationRecord::getChannelId, request.getKeyword());
                }
            });
        }
        wrapper.orderByDesc(NotificationRecord::getSentAt)
                .orderByDesc(NotificationRecord::getCreatedAt);

        Page<NotificationRecord> result = notificationRecordMapper.selectPage(page, wrapper);
        return new PageResult<>(
                notificationRecordConverter.toVOList(result.getRecords()),
                result.getTotal(),
                result.getCurrent(),
                result.getSize()
        );
    }

    @Override
    public NotificationRecordVO getById(String id) {
        NotificationRecord entity = entityValidator.requireExists(
                notificationRecordMapper,
                id,
                ResultCode.NOTIFICATION_RECORD_NOT_FOUND
        );
        return notificationRecordConverter.toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationRecord createSuccessRecord(NotificationDispatchRequest request, NotificationSendResult result) {
        NotificationRecord record = buildBaseRecord(request);
        record.setStatus(STATUS_SUCCESS);
        if (result != null) {
            record.setRequestPayload(result.getRequestPayload());
            record.setResponsePayload(result.getResponsePayload());
            record.setResponseSummary(result.getResponseSummary());
            record.setProviderMessageId(result.getProviderMessageId());
            record.setBillingAmount(result.getBillingAmount());
            record.setBillingCurrency(result.getBillingCurrency());
            record.setBillingUnit(result.getBillingUnit());
            record.setBillingQuantity(result.getBillingQuantity());
            record.setErrorMessage(result.getErrorMessage());
        }
        notificationRecordMapper.insert(record);
        return record;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NotificationRecord createFailureRecord(NotificationDispatchRequest request, Exception exception) {
        NotificationRecord record = buildBaseRecord(request);
        record.setStatus(STATUS_FAILED);
        record.setRequestPayload(buildFallbackRequestPayload(request));
        record.setErrorMessage(exception != null ? exception.getMessage() : "未知错误");
        notificationRecordMapper.insert(record);
        return record;
    }

    private NotificationRecord buildBaseRecord(NotificationDispatchRequest request) {
        NotificationRecord record = new NotificationRecord();
        NotificationChannel channel = request != null ? request.getChannel() : null;
        NotificationMessage message = request != null ? request.getMessage() : null;
        if (channel != null) {
            record.setTenantId(channel.getTenantId());
            record.setChannelId(channel.getId());
            record.setChannelName(channel.getName());
            record.setChannelType(channel.getChannelType());
        }
        if (request != null) {
            if (StringUtils.hasText(request.getTenantId())) {
                record.setTenantId(request.getTenantId());
            }
            record.setTemplateId(request.getTemplateId());
            record.setTemplateName(request.getTemplateName());
            record.setTemplateType(request.getTemplateType());
            record.setSourceType(request.getSourceType());
            record.setSourceId(request.getSourceId());
            record.setBusinessType(request.getBusinessType());
            record.setWorkflowInstanceId(request.getWorkflowInstanceId());
            record.setWorkflowNodeExecutionId(request.getWorkflowNodeExecutionId());
            record.setSpecId(request.getSpecId());
        }
        if (message != null) {
            record.setTitle(message.getTitle());
            record.setContent(message.getContent());
        }
        record.setSentAt(LocalDateTime.now());
        return record;
    }

    private Map<String, Object> buildFallbackRequestPayload(NotificationDispatchRequest request) {
        NotificationMessage message = request != null ? request.getMessage() : null;
        Map<String, Object> payload = new LinkedHashMap<>();
        if (request != null && request.getChannel() != null) {
            payload.put("channelType", request.getChannel().getChannelType());
            payload.put("channelName", request.getChannel().getName());
        }
        if (message != null) {
            payload.put("title", message.getTitle());
            payload.put("content", message.getContent());
            payload.put("previewUrl", message.getPreviewUrl());
        }
        return payload;
    }

    private boolean isUuidKeyword(String keyword) {
        return StringUtils.hasText(keyword) && UUID_PATTERN.matcher(keyword.trim()).matches();
    }
}
