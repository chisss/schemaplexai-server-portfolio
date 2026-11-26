package com.schemaplexai.service.agent.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.model.dto.agent.AgentOntologyBinding;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentExecution;
import com.schemaplexai.service.agent.ontology.AgentOntologyBindingService.Bindings;
import com.schemaplexai.service.agent.ontology.AgentOntologyBindingStore.ExecutionScope;
import com.schemaplexai.service.agent.ontology.AgentOntologyBindingStore.Release;
import com.schemaplexai.service.common.PermissionLoader;
import com.schemaplexai.service.semantic.domain.model.ontology.OntologyConfiguration;
import com.schemaplexai.service.semantic.domain.model.ontology.ReleasedOntologyConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentOntologyRuntimeContextTest {

    private final AgentOntologyBindingService bindings = mock(AgentOntologyBindingService.class);
    private final AgentOntologyBindingStore store = mock(AgentOntologyBindingStore.class);
    private final UserMapper users = mock(UserMapper.class);
    private final PermissionLoader permissions = mock(PermissionLoader.class);
    private final AgentOntologyRuntimeContext runtime = new AgentOntologyRuntimeContext(
            bindings, store, users, permissions, new ObjectMapper());
    private final Agent agent = new Agent();
    private final AgentExecution execution = new AgentExecution();
    private final ExecutionScope scope = new ExecutionScope(3, List.of(new Release("model", "v1", "checksum")));

    @BeforeEach
    void setUp() {
        agent.setId("agent");
        agent.setTenantId("tenant");
        agent.setAgentType("solo");
        execution.setId("execution");
        execution.setAgentId("agent");
        execution.setTenantId("tenant");
        execution.setCreatedBy("owner");
        SecurityUtil.setCurrentTenantId("tenant");
        SecurityUtil.setCurrentUserId("owner");
    }

    @AfterEach
    void clearContext() {
        SecurityUtil.clear();
    }

    @Test
    void pinsFollowActiveVersionAndRendersOnlyLabelsAndBusinessRelations() {
        allowOwner();
        var configured = new AgentOntologyBinding("model", null);
        var fixed = new AgentOntologyBinding("model", "v1");
        when(store.findScope("tenant", "agent", "execution")).thenReturn(Optional.empty(), Optional.of(scope));
        when(bindings.read("tenant", "agent")).thenReturn(new Bindings(3, List.of(configured)));
        when(bindings.release("tenant", configured)).thenReturn(released("checksum"));
        when(bindings.release("tenant", fixed)).thenReturn(released("checksum"));

        var start = runtime.start(agent, execution);
        var resumed = runtime.resume(agent, execution);

        assertThat(start).isEqualTo(resumed);
        assertThat(start.getFirst()).contains("工单", "关联客户", "\"versionId\":\"v1\"")
                .doesNotContain("private.source", "backup_field", "内部描述", "exampleSecretName");
        verify(store).insertScope("tenant", "agent", "execution", scope);
    }

    @Test
    void versionChecksumAndPermissionChangesPreventResume() {
        allowOwner();
        when(store.findScope("tenant", "agent", "execution")).thenReturn(Optional.of(scope));
        when(bindings.release("tenant", new AgentOntologyBinding("model", "v1")))
                .thenReturn(released("changed"));
        assertThatThrownBy(() -> runtime.resume(agent, execution)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本已变化");

        when(permissions.loadActivePermissionCodeSet("tenant", "owner")).thenReturn(Set.of());
        assertThatThrownBy(() -> runtime.resume(agent, execution)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("查看权限");
    }

    @Test
    void privateOutputRequiresOwnerAndCurrentReadPermission() {
        execution.setOntologyPrivate(true);
        SecurityUtil.setCurrentUserId("another-user");
        assertThatThrownBy(() -> runtime.enforceAccess(execution)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(users, permissions);

        SecurityUtil.setCurrentUserId("owner");
        allowOwner();
        runtime.enforceAccess(execution);
        when(permissions.loadActivePermissionCodeSet("tenant", "owner")).thenReturn(Set.of());
        assertThatThrownBy(() -> runtime.enforceAccess(execution)).isInstanceOf(BusinessException.class);
    }

    @Test
    void neverLoadsTeamChildOrCrossTenantConfiguration() {
        agent.setAgentType("team");
        assertThat(runtime.start(agent, execution)).isEmpty();
        agent.setAgentType("solo");
        execution.setParentExecutionId("team-run");
        assertThat(runtime.resume(agent, execution)).isEmpty();
        execution.setParentExecutionId(null);
        agent.setTenantId("other");
        assertThat(runtime.start(agent, execution)).isEmpty();
        verifyNoInteractions(store, bindings, users, permissions);
    }

    @Test
    void legacyExecutionKeepsEmptyScopeEvenWhenBindingIsAddedLater() {
        when(store.findScope("tenant", "agent", "execution")).thenReturn(Optional.empty());
        assertThat(runtime.resume(agent, execution)).isEmpty();
        verifyNoInteractions(bindings, users, permissions);
    }

    @Test
    void taskMentionSelectsConceptAndItsRelationBeforeStableFallback() {
        allowOwner();
        execution.setInputPrompt("核对履约工单的客户关系");
        when(store.findScope("tenant", "agent", "execution")).thenReturn(Optional.of(scope));
        var filler = java.util.stream.IntStream.range(0, 10)
                .mapToObj(index -> new OntologyConfiguration.Concept("a" + index, "concept" + index, "其他概念" + index, null, null))
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        var original = released("checksum").configuration();
        filler.addAll(original.classes());
        var model = new OntologyConfiguration(filler, original.properties(), original.relationships());
        when(bindings.release("tenant", new AgentOntologyBinding("model", "v1")))
                .thenReturn(new ReleasedOntologyConfiguration("tenant", "model", "v1", 7, "checksum", model));

        String result = runtime.resume(agent, execution).getFirst();

        assertThat(result).contains("工单", "客户", "关联客户").doesNotContain("其他概念9");
        assertThat(result.indexOf("工单")).isLessThan(result.indexOf("其他概念"));
    }

    private void allowOwner() {
        when(users.exists(any())).thenReturn(true);
        when(permissions.loadActivePermissionCodeSet("tenant", "owner")).thenReturn(Set.of("semantic:model:view"));
    }

    private ReleasedOntologyConfiguration released(String checksum) {
        var model = new OntologyConfiguration(
                List.of(new OntologyConfiguration.Concept("ticket", "exampleSecretName", "工单", "内部描述", "private.source"),
                        new OntologyConfiguration.Concept("customer", "Customer", "客户", null, null)),
                List.of(new OntologyConfiguration.Property("tag", "ticket", "tag", "机密列", "string", false, "backup_field")),
                List.of(new OntologyConfiguration.Relationship("link", "ticket", "customer", "Link", "关联客户", "RELATION")));
        return new ReleasedOntologyConfiguration("tenant", "model", "v1", 7, checksum, model);
    }
}
