package com.schemaplexai.service.agent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
class AgentFactMemoryServiceTest {
    @Test
    void upsertsStructuredFactsAndIsolatesTenants() throws Exception {
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getenv("DB_TEST_URL"));
        dataSource.setUser(System.getenv().getOrDefault("DB_USER", "schemaplexai"));
        dataSource.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123"));
        var jdbc = new JdbcTemplate(dataSource);
        String schema = "agent_facts_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            dataSource.setCurrentSchema(schema);
            jdbc.execute("CREATE TABLE sf_agent (id UUID PRIMARY KEY, tenant_id UUID NOT NULL, deleted INTEGER)");
            try (var connection = dataSource.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/agent-fact-memory.sql"));
            }
            UUID tenant = UUID.randomUUID();
            UUID otherTenant = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            jdbc.update("INSERT INTO sf_agent VALUES (?, ?, 0)", agent, tenant);
            var service = new AgentFactMemoryService(jdbc);
            var mapper = new ObjectMapper();
            service.save(tenant.toString(), agent.toString(), "priority", mapper.readTree("1"));
            service.save(tenant.toString(), agent.toString(), "priority", mapper.readTree("2"));
            assertThat(service.list(tenant.toString(), agent.toString()))
                    .containsExactly(new AgentFactMemoryService.Fact("priority", "2"));
            assertThat(service.list(otherTenant.toString(), agent.toString())).isEmpty();
            assertThatThrownBy(() -> service.save(otherTenant.toString(), agent.toString(),
                    "priority", mapper.readTree("3"))).isInstanceOf(BusinessException.class);
            service.remove(tenant.toString(), agent.toString(), "priority");
            assertThat(service.list(tenant.toString(), agent.toString())).isEmpty();
        } finally {
            dataSource.setCurrentSchema("public");
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
