package com.schemaplexai.service.context;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.schemaplexai.dao.mapper.AgentContextBindingMapper;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.ContextEntityMapper;
import com.schemaplexai.model.dto.context.ContextResolveRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AgentContextBinding;
import com.schemaplexai.model.entity.ContextEntity;
import com.schemaplexai.model.entity.ContextItem;
import com.schemaplexai.service.context.handler.ContextItemHandler;
import com.schemaplexai.service.context.impl.ContextServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContextServiceResolveTest {

    @Mock
    private AgentMapper agents;
    @Mock
    private AgentContextBindingMapper bindings;
    @Mock
    private ContextEntityMapper contexts;
    @Mock
    private ContextItemHandler items;
    @InjectMocks
    private ContextServiceImpl service;

    @BeforeAll
    static void initializeMapperMetadata() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "context-resolution");
        TableInfoHelper.initTableInfo(assistant, AgentContextBinding.class);
        TableInfoHelper.initTableInfo(assistant, ContextEntity.class);
    }

    @Test
    void omitsProjectTaskAndAgentLayersWhenTheirScopesAreMissing() {
        var result = service.resolve(new ContextResolveRequest());

        assertThat(result.getLayers()).hasSize(4)
                .allSatisfy(layer -> assertThat(layer.getItemCount()).isZero());
        verify(contexts).selectList(any());
        verifyNoInteractions(agents, bindings, items);
    }

    @Test
    void neverReadsBindingsForAnAgentOutsideTheTenantsAgentQuery() {
        var request = new ContextResolveRequest();
        request.setAgentId("other-tenant-agent");

        var result = service.resolve(request);

        assertThat(result.getMergedContent()).isEmpty();
        verify(agents).selectById("other-tenant-agent");
        verify(contexts).selectList(any());
        verifyNoInteractions(bindings, items);
    }

    @Test
    void includesOnlyActiveAgentLevelContextsExplicitlyBoundToTheRequestedAgent() {
        var request = new ContextResolveRequest();
        request.setAgentId("office-agent");
        request.setProjectId("office-move");
        var binding = new AgentContextBinding();
        binding.setContextId("office-context");
        var context = new ContextEntity();
        context.setId("office-context");
        var item = new ContextItem();
        item.setTitle("办公搬迁");
        item.setContent("搬迁负责人为林晨");
        when(agents.selectById("office-agent")).thenReturn(new Agent());
        when(bindings.selectList(any())).thenReturn(List.of(binding));
        when(contexts.selectList(any())).thenReturn(List.of()).thenReturn(List.of())
                .thenReturn(List.of()).thenReturn(List.of(context));
        when(items.loadItems("office-context")).thenReturn(List.of(item));

        var result = service.resolve(request);

        assertThat(result.getLayers().getLast().getContent()).contains("搬迁负责人为林晨");
        ArgumentCaptor<LambdaQueryWrapper<AgentContextBinding>> bindingQuery = ArgumentCaptor.captor();
        verify(bindings).selectList(bindingQuery.capture());
        assertThat(bindingQuery.getValue().getSqlSegment()).contains("agent_id =", "status =", "context_id IS NOT NULL");
        assertThat(bindingQuery.getValue().getParamNameValuePairs()).containsValues("office-agent", "active");

        ArgumentCaptor<LambdaQueryWrapper<ContextEntity>> contextQuery = ArgumentCaptor.captor();
        verify(contexts, times(4)).selectList(contextQuery.capture());
        var queries = contextQuery.getAllValues();
        assertThat(queries.subList(1, 3)).allSatisfy(query -> {
            assertThat(query.getSqlSegment()).contains("project_id =");
            assertThat(query.getParamNameValuePairs()).containsValue("office-move");
        });
        var agentQuery = queries.getLast();
        assertThat(agentQuery.getSqlSegment()).contains("context_level =", "status =", "id IN");
        assertThat(agentQuery.getParamNameValuePairs().values()).containsExactlyInAnyOrder("agent", "active", "office-context");
    }
}
