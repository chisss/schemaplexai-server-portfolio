package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.dao.mapper.semantic.SchemaSnapshotMapper;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaSnapshotMapperTest {

    @Test
    void allSnapshotQueriesRequireTenantAndSource() throws Exception {
        assertTenantScoped("selectByFingerprint", String.class, String.class, String.class);
        assertTenantScoped("selectAllBySource", String.class, String.class);
    }

    private void assertTenantScoped(String methodName, Class<?>... parameterTypes) throws Exception {
        Method method = SchemaSnapshotMapper.class.getMethod(methodName, parameterTypes);
        String sql = method.getAnnotation(Select.class).value()[0];
        assertThat(sql).contains("tenant_id", "#{tenantId}", "source_id", "#{sourceId}", "deleted = 0");
    }
}
