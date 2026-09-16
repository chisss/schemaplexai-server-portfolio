package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.dao.mapper.semantic.SemanticModelMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticModelMapperTest {

    @Test
    void lookupAndUpdateSqlKeepTenantAndRevisionBoundaries() throws NoSuchMethodException {
        Method lookup = SemanticModelMapper.class.getMethod("selectOwned", String.class, String.class);
        Method update = SemanticModelMapper.class.getMethod(
                "updateOwnedWithRevision",
                com.schemaplexai.model.entity.semantic.SemanticModelEntity.class,
                long.class);

        assertThat(sql(lookup.getAnnotation(Select.class).value()))
                .contains("tenant_id", "modelId", "deleted = 0");
        assertThat(sql(update.getAnnotation(Update.class).value()))
                .contains("tenant_id", "expectedRevision", "revision =", "deleted = 0");
    }

    private String sql(String[] fragments) {
        return String.join(" ", fragments);
    }
}
