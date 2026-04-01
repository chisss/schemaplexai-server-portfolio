package com.schemaplexai.service.security;

import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityRuntimeCheckRequest;
import com.schemaplexai.model.vo.security.SecurityCheckDecisionVO;

/**
 * 安全运行时检查服务
 */
public interface SecurityRuntimeGuardService {

    SecurityCheckDecisionVO evaluate(SecurityRuntimeCheckRequest request, SecurityAuditContext auditContext);
}
