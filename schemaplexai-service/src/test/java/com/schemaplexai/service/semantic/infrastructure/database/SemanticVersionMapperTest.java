package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.dao.mapper.semantic.SemanticVersionMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticVersionMapperTest {

    @Test
    void lookupAndUpdateSqlKeepTenantModelAndRevisionBoundaries() throws NoSuchMethodException {
        Method lookup = SemanticVersionMapper.class.getMethod(
                "selectOwned",
                String.class,
                String.class,
                String.class);
        Method update = SemanticVersionMapper.class.getMethod(
                "updateOwnedWithRevision",
                com.schemaplexai.model.entity.semantic.SemanticVersionEntity.class,
                long.class);
        Method nextVersion = SemanticVersionMapper.class.getMethod(
                "selectMaxVersionNo",
                String.class,
                String.class);

        assertThat(sql(lookup.getAnnotation(Select.class).value()))
                .contains("tenant_id", "model_id", "versionId", "deleted = 0");
        assertThat(sql(update.getAnnotation(Update.class).value()))
                .contains("tenant_id", "model_id", "expectedRevision", "revision =", "deleted = 0");
        assertThat(sql(nextVersion.getAnnotation(Select.class).value()))
                .contains("locked_model", "tenant_id", "modelId", "FOR UPDATE");
    }

    private String sql(String[] fragments) {
        return String.join(" ", fragments);
    }
}
