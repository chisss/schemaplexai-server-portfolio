package com.schemaplexai.service.security;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityBindingSaveRequest;
import com.schemaplexai.model.dto.security.SecurityPolicyQueryRequest;
import com.schemaplexai.model.dto.security.SecurityPolicySaveRequest;
import com.schemaplexai.model.dto.security.SecurityPolicyToggleRequest;
import com.schemaplexai.model.vo.security.SecurityBindingVO;
import com.schemaplexai.model.vo.security.SecurityOverviewVO;
import com.schemaplexai.model.vo.security.SecurityPolicyVO;
import com.schemaplexai.model.vo.security.SecurityPolicyVersionVO;
import com.schemaplexai.model.vo.security.SecurityTargetOptionsVO;

import java.util.List;

/**
 * 安全合规服务
 */
public interface SecurityComplianceService {

    SecurityOverviewVO getOverview();

    SecurityTargetOptionsVO listTargetOptions(String scope);

    PageResult<SecurityPolicyVO> pagePolicies(SecurityPolicyQueryRequest request);

    SecurityPolicyVO getPolicyById(String id);

    SecurityPolicyVO createPolicy(SecurityPolicySaveRequest request, SecurityAuditContext auditContext);

    SecurityPolicyVO updatePolicy(String id, SecurityPolicySaveRequest request, SecurityAuditContext auditContext);

    SecurityPolicyVO publishPolicy(String id, SecurityAuditContext auditContext);

    SecurityPolicyVO togglePolicy(String id, SecurityPolicyToggleRequest request, SecurityAuditContext auditContext);

    void deletePolicy(String id, SecurityAuditContext auditContext);

    List<SecurityPolicyVersionVO> listPolicyVersions(String policyId);

    List<SecurityBindingVO> listPolicyBindings(String policyId);

    List<SecurityBindingVO> savePolicyBindings(String policyId, SecurityBindingSaveRequest request, SecurityAuditContext auditContext);
}
