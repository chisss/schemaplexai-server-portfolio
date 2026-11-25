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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Set;
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
                request.getPage() == null ? 1 : Math.max(1, request.getPage()),
                request.getSize() == null ? 20 : Math.clamp(request.getSize(), 1, 100)
        );
        LambdaQueryWrapper<InAppMessageRecipient> wrapper = new LambdaQueryWrapper<InAppMessageRecipient>()
                .eq(InAppMessageRecipient::getRecipientUserId, currentUserId)
                .orderByDesc(InAppMessageRecipient::getCreatedAt).orderByAsc(InAppMessageRecipient::getId);
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(InAppMessageRecipient::getStatus, request.getStatus().trim());
        }
        if (StringUtils.hasText(request.getKeyword()) || StringUtils.hasText(request.getBusinessType())) {
            String keyword = StringUtils.hasText(request.getKeyword()) ? request.getKeyword().trim() : "";
            String type = StringUtils.hasText(request.getBusinessType()) ? request.getBusinessType().trim() : "";
            wrapper.apply("""
                    EXISTS (SELECT 1 FROM sf_in_app_message m
                      WHERE m.id=sf_in_app_message_recipient.message_id
                      AND m.tenant_id=sf_in_app_message_recipient.tenant_id AND m.deleted=0
                      AND ({0}='' OR position({0} in m.title)>0 OR position({0} in m.content)>0
                           OR position({0} in m.business_type)>0)
                      AND ({1}='' OR m.business_type={1}))
                    """, keyword, type);
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

    @Override
    public InAppMessageVO detail(String recipientId) {
        var recipient = requireOwnedRecipient(recipientId);
        var result = toVO(recipient, inAppMessageMapper.selectById(recipient.getMessageId()));
        if (result == null) throw new BusinessException(ResultCode.NOT_FOUND, "站内信不存在");
        return result;
    }

    @Override
    public long markAllRead(OffsetDateTime readBefore) {
        var now = LocalDateTime.now();
        var watermark = readBefore.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        if (watermark.isAfter(now)) watermark = now;
        return recipientMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InAppMessageRecipient>()
                .eq(InAppMessageRecipient::getTenantId, SecurityUtil.getCurrentTenantId())
                .eq(InAppMessageRecipient::getRecipientUserId, SecurityUtil.getCurrentUserId())
                .eq(InAppMessageRecipient::getStatus, InAppMessageRecipientStatusEnum.UNREAD.getCode())
                .le(InAppMessageRecipient::getCreatedAt, watermark)
                .set(InAppMessageRecipient::getStatus, InAppMessageRecipientStatusEnum.READ.getCode())
                .set(InAppMessageRecipient::getReadAt, now)
                .set(InAppMessageRecipient::getUpdatedAt, now));
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
        if (message.getSourceType() != null && message.getSourceId() != null
                && Set.of("review_session", "workflow_review", "quality_issue", "security_incident",
                          "agent_execution", "workflow_instance", "desktop_schedule").contains(message.getSourceType())) {
            vo.setTarget(new InAppMessageVO.Target(message.getSourceType(), message.getSourceId()));
        }
        vo.setStatus(recipient.getStatus());
        vo.setReadAt(recipient.getReadAt());
        vo.setArchivedAt(recipient.getArchivedAt());
        vo.setPayload(message.getPayload());
        vo.setCreatedAt(message.getCreatedAt());
        return vo;
    }

    private InAppMessageRecipient requireOwnedRecipient(String recipientId) {
        InAppMessageRecipient recipient = recipientMapper.selectById(recipientId);
        if (recipient == null || !Objects.equals(SecurityUtil.getCurrentUserId(), recipient.getRecipientUserId())
                || !Objects.equals(SecurityUtil.getCurrentTenantId(), recipient.getTenantId())) {
            throw new BusinessException(ResultCode.NOT_FOUND, "站内信不存在");
        }
        return recipient;
    }
}
