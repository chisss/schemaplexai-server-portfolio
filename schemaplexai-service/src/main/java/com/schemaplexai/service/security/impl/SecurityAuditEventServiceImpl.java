package com.schemaplexai.service.security.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SecurityAuditEventMapper;
import com.schemaplexai.model.converter.SecurityAuditEventConverter;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityAuditEventQueryRequest;
import com.schemaplexai.model.entity.SecurityAuditEvent;
import com.schemaplexai.model.entity.SecurityPolicy;
import com.schemaplexai.model.vo.security.SecurityAuditEventVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.security.SecurityAuditEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 安全审计事件服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityAuditEventServiceImpl implements SecurityAuditEventService {

    private static final int RECENT_EVENT_MAX_SIZE = 20;

    private final SecurityAuditEventMapper securityAuditEventMapper;
    private final SecurityAuditEventConverter securityAuditEventConverter;
    private final EntityValidator entityValidator;

    @Override
    public PageResult<SecurityAuditEventVO> page(SecurityAuditEventQueryRequest request) {
        var page = new Page<SecurityAuditEvent>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<SecurityAuditEvent>();

        if (StringUtils.hasText(request.getDomainCode())) {
            wrapper.eq(SecurityAuditEvent::getDomainCode, request.getDomainCode());
        }
        if (StringUtils.hasText(request.getEventType())) {
            wrapper.eq(SecurityAuditEvent::getEventType, request.getEventType());
        }
        if (StringUtils.hasText(request.getEventStatus())) {
            wrapper.eq(SecurityAuditEvent::getEventStatus, request.getEventStatus());
        }
        if (StringUtils.hasText(request.getRiskLevel())) {
            wrapper.eq(SecurityAuditEvent::getRiskLevel, request.getRiskLevel());
        }
        if (StringUtils.hasText(request.getTraceId())) {
            wrapper.eq(SecurityAuditEvent::getTraceId, request.getTraceId());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(q -> q.like(SecurityAuditEvent::getEventTitle, request.getKeyword())
                    .or()
                    .like(SecurityAuditEvent::getEventDetail, request.getKeyword())
                    .or()
                    .like(SecurityAuditEvent::getPolicyCode, request.getKeyword())
                    .or()
                    .like(SecurityAuditEvent::getActorUsername, request.getKeyword()));
        }
        if (request.getStartTime() != null) {
            wrapper.ge(SecurityAuditEvent::getOccurredAt, request.getStartTime());
        }
        if (request.getEndTime() != null) {
            wrapper.le(SecurityAuditEvent::getOccurredAt, request.getEndTime());
        }
        wrapper.orderByDesc(SecurityAuditEvent::getOccurredAt);

        var result = securityAuditEventMapper.selectPage(page, wrapper);
        var records = securityAuditEventConverter.toVOList(result.getRecords());
        return new PageResult<>(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public SecurityAuditEventVO getById(String id) {
        var entity = entityValidator.requireExists(
                securityAuditEventMapper,
                id,
                ResultCode.SECURITY_AUDIT_EVENT_NOT_FOUND
        );
        return securityAuditEventConverter.toVO(entity);
    }

    @Override
    public List<SecurityAuditEventVO> listRecent(int limit) {
        int finalLimit = Math.min(Math.max(limit, 1), RECENT_EVENT_MAX_SIZE);
        var wrapper = new LambdaQueryWrapper<SecurityAuditEvent>()
                .orderByDesc(SecurityAuditEvent::getOccurredAt)
                .last("LIMIT " + finalLimit);
        return securityAuditEventConverter.toVOList(securityAuditEventMapper.selectList(wrapper));
    }

    @Override
    public List<SecurityAuditEventVO> listByTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return List.of();
        }
        var wrapper = new LambdaQueryWrapper<SecurityAuditEvent>()
                .eq(SecurityAuditEvent::getTraceId, traceId)
                .orderByAsc(SecurityAuditEvent::getOccurredAt);
        return securityAuditEventConverter.toVOList(securityAuditEventMapper.selectList(wrapper));
    }

    @Override
    public void recordPolicyEvent(SecurityPolicy policy,
                                  String eventType,
                                  String eventStatus,
                                  String eventTitle,
                                  String eventDetail,
                                  Map<String, Object> metadata,
                                  SecurityAuditContext auditContext) {
        if (policy == null) {
            return;
        }
        recordEvent(
                policy.getTenantId(),
                UUID.randomUUID().toString().replace("-", ""),
                eventType,
                SecurityComplianceConstant.AUDIT_SOURCE_STRATEGY_CENTER,
                eventStatus,
                policy.getRiskLevel(),
                policy.getDomainCode(),
                policy.getId(),
                policy.getPolicyCode(),
                SecurityComplianceConstant.RESOURCE_TYPE_POLICY,
                policy.getId(),
                eventTitle,
                eventDetail,
                metadata,
                auditContext
        );
    }

    @Override
    public void recordEvent(String tenantId,
                            String traceId,
                            String eventType,
                            String eventSource,
                            String eventStatus,
                            String riskLevel,
                            String domainCode,
                            String policyId,
                            String policyCode,
                            String resourceType,
                            String resourceId,
                            String eventTitle,
                            String eventDetail,
                            Map<String, Object> metadata,
                            SecurityAuditContext auditContext) {
        var event = new SecurityAuditEvent();
        event.setTenantId(tenantId);
        event.setTraceId(StringUtils.hasText(traceId) ? traceId : UUID.randomUUID().toString().replace("-", ""));
        event.setEventType(eventType);
        event.setEventSource(eventSource);
        event.setEventStatus(eventStatus);
        event.setRiskLevel(riskLevel);
        event.setDomainCode(domainCode);
        event.setPolicyId(policyId);
        event.setPolicyCode(policyCode);
        event.setResourceType(resourceType);
        event.setResourceId(resourceId);
        event.setEventTitle(eventTitle);
        event.setEventDetail(eventDetail);
        event.setActorUserId(SecurityUtil.getCurrentUserId());
        event.setActorUsername(resolveUsername());
        event.setClientIp(auditContext != null ? auditContext.getClientIp() : null);
        event.setUserAgent(auditContext != null ? auditContext.getUserAgent() : null);
        event.setMetadata(metadata);
        event.setOccurredAt(LocalDateTime.now());
        securityAuditEventMapper.insert(event);
        log.info("记录安全审计事件成功: eventType={}, traceId={}, resourceType={}, resourceId={}",
                eventType, event.getTraceId(), resourceType, resourceId);
    }

    private String resolveUsername() {
        if (StringUtils.hasText(SecurityUtil.getCurrentUsername())) {
            return SecurityUtil.getCurrentUsername();
        }
        return "system";
    }
}
