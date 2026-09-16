package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.dao.mapper.semantic.SemanticVersionMapper;
import com.schemaplexai.model.entity.semantic.SemanticVersionEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticVersionRepositoryAdapterTest {

    @Test
    void scopesVersionLookupToTenantAndModel() {
        SemanticVersionMapper mapper = mock(SemanticVersionMapper.class);
        SemanticVersionEntity entity = new SemanticVersionEntity();
        entity.setId("version-1");
        entity.setTenantId("tenant-a");
        entity.setModelId("model-1");
        entity.setVersionNo(1);
        entity.setGraphIri("urn:spx:tenant:tenant-a:semantic:model-1:v:1:asserted");
        entity.setStatus("draft");
        entity.setTripleCount(0L);
        entity.setRevision(0L);
        when(mapper.selectOwned("tenant-a", "model-1", "version-1")).thenReturn(entity);
        MybatisSemanticVersionRepository repository = new MybatisSemanticVersionRepository(mapper);

        var result = repository.findByTenantModelAndId("tenant-a", "model-1", "version-1");

        assertThat(result).isPresent();
        verify(mapper).selectOwned("tenant-a", "model-1", "version-1");
    }
}
