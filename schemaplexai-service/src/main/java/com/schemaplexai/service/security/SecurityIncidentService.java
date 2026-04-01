package com.schemaplexai.service.security;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityIncidentActionRequest;
import com.schemaplexai.model.dto.security.SecurityIncidentQueryRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;
import com.schemaplexai.model.vo.security.SecurityIncidentActionVO;
import com.schemaplexai.model.vo.security.SecurityIncidentVO;

import java.util.List;

/**
 * 安全事件服务
 */
public interface SecurityIncidentService {

    PageResult<SecurityIncidentVO> page(SecurityIncidentQueryRequest request);

    SecurityIncidentVO getById(String id);

    List<SecurityIncidentVO> listRecent(int limit);

    List<SecurityIncidentActionVO> listActions(String incidentId);

    SecurityIncidentVO assign(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext);

    SecurityIncidentVO resolve(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext);

    SecurityIncidentVO ignore(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext);

    SecurityIncidentVO escalate(String id, SecurityIncidentActionRequest request, SecurityAuditContext auditContext);

    SecurityIncidentVO resumeResource(String id, SecurityAuditContext auditContext);

    String createFromDecision(SecurityCheckDecisionVO decision,
                              String tenantId,
                              String domainCode,
                              String sourceType,
                              String sourceId,
                              String sourceName);
}
