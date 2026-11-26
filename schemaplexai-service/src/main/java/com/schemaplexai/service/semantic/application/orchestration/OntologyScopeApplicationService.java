package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.domain.model.scope.OntologyScope;
import com.schemaplexai.service.semantic.domain.repository.OntologyScopeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OntologyScopeApplicationService {

    private final OntologyScopeRepository repository;

    public List<OntologyScope> list() {
        return repository.list(requireTenant());
    }

    public OntologyScope create(String systemName, String domainName) {
        return repository.createOrGet(requireTenant(), requireName(systemName), requireName(domainName));
    }

    public OntologyScope require(String scopeId) {
        String tenantId = requireTenant();
        String canonicalId;
        try {
            canonicalId = UUID.fromString(scopeId).toString();
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "业务范围标识无效");
        }
        return repository.find(tenantId, canonicalId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "业务范围不存在"));
    }

    private String requireName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 100) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "系统与业务域名称必须为 1 至 100 个字符");
        }
        return name.strip();
    }

    private String requireTenant() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId;
    }
}
