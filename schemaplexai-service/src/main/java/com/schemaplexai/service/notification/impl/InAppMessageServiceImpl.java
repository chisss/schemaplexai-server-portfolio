package com.schemaplexai.service.notification.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.InAppMessageRecipientStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.InAppMessageMapper;
import com.schemaplexai.dao.mapper.InAppMessageRecipientMapper;
import com.schemaplexai.model.dto.notification.InAppMessageQueryRequest;
import com.schemaplexai.model.entity.InAppMessage;
import com.schemaplexai.model.entity.InAppMessageRecipient;
import com.schemaplexai.model.vo.notification.InAppMessageVO;
import com.schemaplexai.service.notification.InAppMessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 站内信服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InAppMessageServiceImpl implements InAppMessageService {

    private final InAppMessageMapper inAppMessageMapper;
    private final InAppMessageRecipientMapper recipientMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createMessage(String tenantId,
                              String title,
                              String content,
                              String messageType,
                              String businessType,
                              String sourceType,
                              String sourceId,
                              String actionUrl,
                              Map<String, Object> payload,
                              List<String> recipientUserIds) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(title)
                || !StringUtils.hasText(content) || CollectionUtils.isEmpty(recipientUserIds)) {
            return;
        }

        List<String> distinctRecipients = recipientUserIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (distinctRecipients.isEmpty()) {
            return;
        }

        InAppMessage message = new InAppMessage();
        message.setTenantId(tenantId);
        message.setTitle(title);
        message.setContent(content);
        message.setMessageType(StringUtils.hasText(messageType) ? messageType : "notification");
        message.setBusinessType(businessType);
        message.setSourceType(sourceType);
        message.setSourceId(sourceId);
        message.setActionUrl(actionUrl);
        message.setPayload(payload == null ? Map.of() : new HashMap<>(payload));
        inAppMessageMapper.insert(message);

        for (String recipientUserId : distinctRecipients) {
            InAppMessageRecipient recipient = new InAppMessageRecipient();
            recipient.setTenantId(tenantId);
            recipient.setMessageId(message.getId());
            recipient.setRecipientUserId(recipientUserId);
            recipient.setStatus(InAppMessageRecipientStatusEnum.UNREAD.getCode());
            recipientMapper.insert(recipient);
        }
        log.info("创建站内信成功: messageId={}, recipientCount={}", message.getId(), distinctRecipients.size());
    }

    @Override
    public PageResult<InAppMessageVO> pageInbox(InAppMessageQueryRequest request) {
        String currentUserId = SecurityUtil.getCurrentUserId();
        Page<InAppMessageRecipient> page = new Page<>(
                request.getPage() == null ? 1 : request.getPage(),
                request.getSize() == null ? 20 : request.getSize()
        );
        LambdaQueryWrapper<InAppMessageRecipient> wrapper = new LambdaQueryWrapper<InAppMessageRecipient>()
                .eq(InAppMessageRecipient::getRecipientUserId, currentUserId)
                .orderByDesc(InAppMessageRecipient::getCreatedAt);
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(InAppMessageRecipient::getStatus, request.getStatus().trim());
        }
        Page<InAppMessageRecipient> result = recipientMapper.selectPage(page, wrapper);
        List<InAppMessageRecipient> recipients = result.getRecords();
        if (recipients.isEmpty()) {
            return new PageResult<>(List.of(), result.getTotal(), result.getCurrent(), result.getSize());
        }

        Map<String, InAppMessage> messageMap = inAppMessageMapper.selectBatchIds(
                        recipients.stream().map(InAppMessageRecipient::getMessageId).filter(Objects::nonNull).distinct().toList())
                .stream()
                .collect(Collectors.toMap(InAppMessage::getId, item -> item));

        List<InAppMessageVO> records = recipients.stream()
                .map(recipient -> toVO(recipient, messageMap.get(recipient.getMessageId())))
                .filter(Objects::nonNull)
                .filter(vo -> !StringUtils.hasText(request.getKeyword())
                        || containsKeyword(vo, request.getKeyword().trim()))
                .toList();
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public long countUnread() {
        return recipientMapper.selectCount(
                new LambdaQueryWrapper<InAppMessageRecipient>()
                        .eq(InAppMessageRecipient::getRecipientUserId, SecurityUtil.getCurrentUserId())
                        .eq(InAppMessageRecipient::getStatus, InAppMessageRecipientStatusEnum.UNREAD.getCode())
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRead(String recipientId) {
        InAppMessageRecipient recipient = requireOwnedRecipient(recipientId);
        if (InAppMessageRecipientStatusEnum.ARCHIVED.getCode().equals(recipient.getStatus())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "已归档消息不允许标记为已读");
        }
        recipient.setStatus(InAppMessageRecipientStatusEnum.READ.getCode());
        recipient.setReadAt(LocalDateTime.now());
        recipient.setUpdatedAt(LocalDateTime.now());
        recipientMapper.updateById(recipient);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archive(String recipientId) {
        InAppMessageRecipient recipient = requireOwnedRecipient(recipientId);
        recipient.setStatus(InAppMessageRecipientStatusEnum.ARCHIVED.getCode());
        recipient.setArchivedAt(LocalDateTime.now());
        if (recipient.getReadAt() == null) {
            recipient.setReadAt(LocalDateTime.now());
        }
        recipient.setUpdatedAt(LocalDateTime.now());
        recipientMapper.updateById(recipient);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archiveBySource(String sourceType, String sourceId) {
        if (!StringUtils.hasText(sourceType) || !StringUtils.hasText(sourceId)) {
            return;
        }
        List<String> messageIds = inAppMessageMapper.selectList(
                        new LambdaQueryWrapper<InAppMessage>()
                                .eq(InAppMessage::getSourceType, sourceType)
                                .eq(InAppMessage::getSourceId, sourceId)
                ).stream()
                .map(InAppMessage::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (messageIds.isEmpty()) {
            return;
        }

        List<InAppMessageRecipient> recipients = recipientMapper.selectList(
                new LambdaQueryWrapper<InAppMessageRecipient>()
                        .in(InAppMessageRecipient::getMessageId, messageIds)
                        .ne(InAppMessageRecipient::getStatus, InAppMessageRecipientStatusEnum.ARCHIVED.getCode())
        );
        if (recipients.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (InAppMessageRecipient recipient : recipients) {
            recipient.setStatus(InAppMessageRecipientStatusEnum.ARCHIVED.getCode());
            recipient.setArchivedAt(now);
            if (recipient.getReadAt() == null) {
                recipient.setReadAt(now);
            }
            recipient.setUpdatedAt(now);
            recipientMapper.updateById(recipient);
        }
        log.info("按来源归档站内信成功: sourceType={}, sourceId={}, recipientCount={}",
                sourceType, sourceId, recipients.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void archivePendingReviewMessages(String tenantId,
                                             String specId,
                                             String workflowNodeId,
                                             String excludeSourceId) {
        if (!StringUtils.hasText(tenantId)
                || !StringUtils.hasText(specId)
                || !StringUtils.hasText(workflowNodeId)) {
            return;
        }

        LambdaQueryWrapper<InAppMessage> messageWrapper = new LambdaQueryWrapper<InAppMessage>()
                .eq(InAppMessage::getTenantId, tenantId)
                .eq(InAppMessage::getSourceType, "workflow_human_review")
                .eq(InAppMessage::getBusinessType, "human_review_pending")
                .apply("payload ->> 'specId' = {0}", specId)
                .apply("payload ->> 'workflowNodeId' = {0}", workflowNodeId);
        if (StringUtils.hasText(excludeSourceId)) {
            messageWrapper.ne(InAppMessage::getSourceId, excludeSourceId);
        }

        List<String> messageIds = inAppMessageMapper.selectList(messageWrapper).stream()
                .map(InAppMessage::getId)
                .filter(StringUtils::hasText)
                .toList();
        if (messageIds.isEmpty()) {
            return;
        }

        List<InAppMessageRecipient> recipients = recipientMapper.selectList(
                new LambdaQueryWrapper<InAppMessageRecipient>()
                        .in(InAppMessageRecipient::getMessageId, messageIds)
                        .ne(InAppMessageRecipient::getStatus, InAppMessageRecipientStatusEnum.ARCHIVED.getCode())
        );
        if (recipients.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        for (InAppMessageRecipient recipient : recipients) {
            recipient.setStatus(InAppMessageRecipientStatusEnum.ARCHIVED.getCode());
            recipient.setArchivedAt(now);
            if (recipient.getReadAt() == null) {
                recipient.setReadAt(now);
            }
            recipient.setUpdatedAt(now);
            recipientMapper.updateById(recipient);
        }
        log.info("按评审目标归档站内信成功: specId={}, workflowNodeId={}, recipientCount={}",
                specId, workflowNodeId, recipients.size());
    }

    private boolean containsKeyword(InAppMessageVO vo, String keyword) {
        return (vo.getTitle() != null && vo.getTitle().contains(keyword))
                || (vo.getContent() != null && vo.getContent().contains(keyword))
                || (vo.getBusinessType() != null && vo.getBusinessType().contains(keyword));
    }

    private InAppMessageVO toVO(InAppMessageRecipient recipient, InAppMessage message) {
        if (recipient == null || message == null) {
            return null;
        }
        InAppMessageVO vo = new InAppMessageVO();
        vo.setRecipientId(recipient.getId());
        vo.setMessageId(message.getId());
        vo.setTitle(message.getTitle());
        vo.setContent(message.getContent());
        vo.setMessageType(message.getMessageType());
        vo.setBusinessType(message.getBusinessType());
        vo.setSourceType(message.getSourceType());
        vo.setSourceId(message.getSourceId());
        vo.setActionUrl(message.getActionUrl());
        vo.setStatus(recipient.getStatus());
        vo.setReadAt(recipient.getReadAt());
        vo.setArchivedAt(recipient.getArchivedAt());
        vo.setPayload(message.getPayload());
        vo.setCreatedAt(message.getCreatedAt());
        return vo;
    }

    private InAppMessageRecipient requireOwnedRecipient(String recipientId) {
        InAppMessageRecipient recipient = recipientMapper.selectById(recipientId);
        if (recipient == null || !SecurityUtil.getCurrentUserId().equals(recipient.getRecipientUserId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "站内信不存在");
        }
        return recipient;
    }
}
