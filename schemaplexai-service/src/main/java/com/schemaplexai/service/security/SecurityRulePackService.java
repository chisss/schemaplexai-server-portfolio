package com.schemaplexai.service.security;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.dto.security.SecurityBindingSaveRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackQueryRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackSaveRequest;
import com.schemaplexai.model.dto.security.SecurityRulePackToggleRequest;
import com.schemaplexai.model.vo.security.SecurityBindingVO;
import com.schemaplexai.model.vo.security.SecurityRuleItemVO;
import com.schemaplexai.model.vo.security.SecurityRulePackVO;

import java.util.List;

/**
 * 安全规则包服务
 */
public interface SecurityRulePackService {

    PageResult<SecurityRulePackVO> page(SecurityRulePackQueryRequest request);

    SecurityRulePackVO getById(String id);

    SecurityRulePackVO create(SecurityRulePackSaveRequest request, SecurityAuditContext auditContext);

    SecurityRulePackVO update(String id, SecurityRulePackSaveRequest request, SecurityAuditContext auditContext);

    SecurityRulePackVO toggle(String id, SecurityRulePackToggleRequest request, SecurityAuditContext auditContext);

    List<SecurityRuleItemVO> listItems(String packId);

    List<SecurityBindingVO> listBindings(String packId);

    List<SecurityBindingVO> saveBindings(String packId, SecurityBindingSaveRequest request, SecurityAuditContext auditContext);
}
