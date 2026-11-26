package com.schemaplexai.service.agent.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.model.dto.agent.AgentOntologyBinding;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.ontology.AgentOntologyBindingStore.ExecutionScope;
import com.schemaplexai.service.agent.ontology.AgentOntologyBindingStore.Release;
import com.schemaplexai.service.common.PermissionLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@EnabledIfEnvironmentVariable(named = "ONTOLOGY_PG_TEST_URL", matches = ".+")
class AgentOntologyBindingStoreTest {

    @Test
    void appliesBindingCasAndKeepsImmutableTenantExecutionScope() throws Exception {
        String url = System.getenv("ONTOLOGY_PG_TEST_URL");
        String user = System.getenv().getOrDefault("ONTOLOGY_PG_TEST_USER", "postgres");
        String password = System.getenv().getOrDefault("ONTOLOGY_PG_TEST_PASSWORD", "");
        String namespace = "agent_ontology_" + UUID.randomUUID().toString().replace("-", "");
        var bootstrap = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        bootstrap.execute("CREATE SCHEMA " + namespace);
        try {
            String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + namespace;
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(scopedUrl, user, password));
            jdbc.execute("CREATE TABLE sf_agent(id uuid PRIMARY KEY)");
            jdbc.execute("CREATE TABLE sf_agent_execution(id uuid PRIMARY KEY)");
            var script = "document/sql/ddl/12-Agent业务本体绑定.sql";
            Path scriptPath = Path.of("..", "..", script);
            jdbc.execute(Files.readString(scriptPath));
            var store = new AgentOntologyBindingStore(jdbc, new ObjectMapper());
            var agent = UUID.randomUUID();
            var execution = UUID.randomUUID();
            var unboundExecution = UUID.randomUUID();
            String tenant = UUID.randomUUID().toString();
            String anotherTenant = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO sf_agent VALUES (?)", agent);
            jdbc.update("INSERT INTO sf_agent_execution VALUES (?)", execution);
            jdbc.update("INSERT INTO sf_agent_execution VALUES (?)", unboundExecution);
            var binding = new AgentOntologyBinding(UUID.randomUUID().toString(), null);

            var bindingService = new AgentOntologyBindingService(null, store, null, null);
            var users = mock(UserMapper.class);
            var permissionLoader = mock(PermissionLoader.class);
            var runtime = new AgentOntologyRuntimeContext(bindingService, store, users, permissionLoader, new ObjectMapper());
            Agent solo = new Agent();
            solo.setAgentType("solo");
            solo.setTenantId(tenant);
            solo.setId(agent.toString());
            AgentExecution previous = new AgentExecution();
            previous.setId(unboundExecution.toString());
            previous.setTenantId(tenant);
            previous.setAgentId(agent.toString());
            assertThat(runtime.start(solo, previous)).isEmpty();
            assertThat(store.findScope(tenant, agent.toString(), unboundExecution.toString()))
                    .contains(new ExecutionScope(0, List.of()));

            assertThat(store.replaceBindings(tenant, agent.toString(), 0, List.of(binding))).isTrue();
            assertThat(store.replaceBindings(tenant, agent.toString(), 0, List.of())).isFalse();
            assertThat(store.findBindings(tenant, agent.toString())).get()
                    .isEqualTo(new AgentOntologyBindingService.Bindings(1, List.of(binding)));
            assertThat(store.findBindings(anotherTenant, agent.toString())).isEmpty();
            assertThat(runtime.resume(solo, previous)).isEmpty();
            verifyNoInteractions(users, permissionLoader);

            var frozen = new ExecutionScope(1, List.of(new Release(binding.modelId(), "version", "checksum")));
            store.insertScope(tenant, agent.toString(), execution.toString(), frozen);
            assertThat(store.replaceBindings(tenant, agent.toString(), 1, List.of())).isTrue();
            store.insertScope(tenant, agent.toString(), execution.toString(), new ExecutionScope(2, List.of()));
            assertThat(store.findScope(tenant, agent.toString(), execution.toString().replace("-", "")))
                    .contains(frozen);
            assertThat(store.findScope(anotherTenant, agent.toString(), execution.toString())).isEmpty();
            assertThat(store.findScope(tenant, UUID.randomUUID().toString(), execution.toString())).isEmpty();
        } finally {
            bootstrap.execute("DROP SCHEMA " + namespace + " CASCADE");
        }
    }
}
