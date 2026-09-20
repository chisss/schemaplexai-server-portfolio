package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.schemaplexai.model.entity.AuditLog;
import com.schemaplexai.service.semantic.domain.model.query.QueryAuditEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MybatisSemanticQueryAuditAdapterTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityUtil.clear();
    }

    @Test
    void persistsTenantScopedSummaryWithoutQueryTextOrParameterValues() throws Exception {
        SecurityUtil.setCurrentUsername("analyst-a");
        var adapter = new MybatisSemanticQueryAuditAdapter(auditLogMapper, new ObjectMapper());
        adapter.record(new QueryAuditEvent(
                "audit-1", "tenant-a", "user-a", "plan-hash", "source-1", "EXECUTE", "SUCCESS", 4, 28,
                Instant.parse("2026-09-20T05:00:00Z")));

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getTenantId()).isEqualTo("tenant-a");
        assertThat(log.getUserId()).isEqualTo("user-a");
        assertThat(log.getUsername()).isEqualTo("analyst-a");
        assertThat(log.getAction()).isEqualTo("semantic_query.EXECUTE");
        assertThat(log.getResource()).isEqualTo("semantic_query");
        assertThat(log.getResourceId()).isEqualTo("plan-hash");
        JsonNode detail = new ObjectMapper().readTree(log.getDetail());
        assertThat(detail.get("sourceId").asText()).isEqualTo("source-1");
        assertThat(detail.get("rowCount").asInt()).isEqualTo(4);
        assertThat(log.getDetail()).doesNotContain("SELECT", "completed", "password", "pipeline");
    }
}
