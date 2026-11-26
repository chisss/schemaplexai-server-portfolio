package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.semantic.domain.model.scope.OntologyScope;
import com.schemaplexai.service.semantic.domain.repository.OntologyScopeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class JdbcOntologyScopeRepository implements OntologyScopeRepository {

    private static final RowMapper<OntologyScope> SCOPE_MAPPER = (row, number) ->
            new OntologyScope(row.getString("id"), row.getString("system_name"), row.getString("domain_name"));

    private final JdbcTemplate jdbc;

    @Override
    public List<OntologyScope> list(String tenantId) {
        return jdbc.query("""
                SELECT id, system_name, domain_name FROM sf_ontology_scope
                WHERE tenant_id = CAST(? AS UUID)
                ORDER BY LOWER(system_name), LOWER(domain_name), id
                """, SCOPE_MAPPER, tenantId);
    }

    @Override
    public Optional<OntologyScope> find(String tenantId, String scopeId) {
        return jdbc.query("""
                SELECT id, system_name, domain_name FROM sf_ontology_scope
                WHERE tenant_id = CAST(? AS UUID) AND id = CAST(? AS UUID)
                """, SCOPE_MAPPER, tenantId, scopeId).stream().findFirst();
    }

    @Override
    public OntologyScope createOrGet(String tenantId, String systemName, String domainName) {
        return jdbc.queryForObject("""
                INSERT INTO sf_ontology_scope(tenant_id, system_name, domain_name)
                VALUES (CAST(? AS UUID), ?, ?)
                ON CONFLICT (tenant_id, LOWER(system_name), LOWER(domain_name))
                DO UPDATE SET system_name = sf_ontology_scope.system_name
                RETURNING id, system_name, domain_name
                """, SCOPE_MAPPER, tenantId, systemName, domainName);
    }
}
