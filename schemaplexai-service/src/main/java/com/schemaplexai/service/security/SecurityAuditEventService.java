package com.schemaplexai.service.security;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityAuditEventQueryRequest;
import com.schemaplexai.model.entity.SecurityPolicy;
import com.schemaplexai.model.vo.security.SecurityAuditEventVO;

import java.util.List;
import java.util.Map;

/**
 * 安全审计事件服务
 */
public interface SecurityAuditEventService {

    PageResult<SecurityAuditEventVO> page(SecurityAuditEventQueryRequest request);

    SecurityAuditEventVO getById(String id);

    List<SecurityAuditEventVO> listRecent(int limit);

    List<SecurityAuditEventVO> listByTraceId(String traceId);

    void recordPolicyEvent(SecurityPolicy policy,
                           String eventType,
                           String eventStatus,
                           String eventTitle,
                           String eventDetail,
                           Map<String, Object> metadata,
                           SecurityAuditContext auditContext);

    void recordEvent(String tenantId,
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
                     SecurityAuditContext auditContext);
}
