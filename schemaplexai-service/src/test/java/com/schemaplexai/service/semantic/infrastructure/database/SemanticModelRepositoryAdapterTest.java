package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.dao.mapper.semantic.SemanticModelMapper;
import com.schemaplexai.model.entity.semantic.SemanticModelEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticModelRepositoryAdapterTest {

    @Test
    void scopesModelLookupToExplicitTenant() {
        SemanticModelMapper mapper = mock(SemanticModelMapper.class);
        SemanticModelEntity entity = new SemanticModelEntity();
        entity.setId("model-1");
        entity.setTenantId("tenant-a");
        entity.setName("订单");
        entity.setDomain("sales");
        entity.setStatus("active");
        entity.setRevision(0L);
        when(mapper.selectOwned("tenant-a", "model-1")).thenReturn(entity);
        MybatisSemanticModelRepository repository = new MybatisSemanticModelRepository(mapper);

        var result = repository.findByTenantAndId("tenant-a", "model-1");

        assertThat(result).isPresent();
        verify(mapper).selectOwned("tenant-a", "model-1");
    }
}
