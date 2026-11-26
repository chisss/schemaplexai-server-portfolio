package com.schemaplexai.service.semantic.domain.repository;

import com.schemaplexai.service.semantic.domain.model.scope.OntologyScope;

import java.util.List;
import java.util.Optional;

public interface OntologyScopeRepository {

    List<OntologyScope> list(String tenantId);

    Optional<OntologyScope> find(String tenantId, String scopeId);

    OntologyScope createOrGet(String tenantId, String systemName, String domainName);
}
