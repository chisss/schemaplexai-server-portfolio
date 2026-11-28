package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.storage.DocumentStorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class AgentExecutionEvidenceServiceTest {
    @Test
    void readsLargeArtifactPagesWithoutSplittingUnicodeOrReturningEarly() throws Exception {
        var storage = mock(DocumentStorageService.class);
        var service = new AgentExecutionEvidenceService(storage, mock(JdbcTemplate.class));
        String tenant = UUID.randomUUID().toString();
        String execution = UUID.randomUUID().toString();
        String artifact = UUID.randomUUID().toString();
        String first = "测".repeat(3_999) + "📄";
        String second = "次".repeat(4_000);
        String third = "末".repeat(4_000);
        byte[] content = (first + second + third + "终").getBytes(StandardCharsets.UTF_8);
        when(storage.getDefaultBucket()).thenReturn("artifacts");
        when(storage.getObject(eq("artifacts"), anyString())).thenAnswer(call -> new ByteArrayInputStream(content) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 97));
            }
        });
        assertThat(service.readArtifactPage(tenant, execution, artifact, 0))
                .isEqualTo(new AgentExecutionEvidenceService.EvidencePage(first, 4_000));
        assertThat(service.readArtifactPage(tenant, execution, artifact, 4_000))
                .isEqualTo(new AgentExecutionEvidenceService.EvidencePage(second, 8_000));
        assertThat(service.readArtifactPage(tenant, execution, artifact, 8_000))
                .isEqualTo(new AgentExecutionEvidenceService.EvidencePage(third, 12_000));
        assertThat(service.readArtifactPage(tenant, execution, artifact, 12_000))
                .isEqualTo(new AgentExecutionEvidenceService.EvidencePage("终", null));
        assertThat(service.readArtifactPage(tenant, execution, artifact, 12_002))
                .isEqualTo(new AgentExecutionEvidenceService.EvidencePage("", null));
    }

    @EnabledIfEnvironmentVariable(named = "DB_TEST_URL", matches = ".+")
    @Test
    void pagesFullOutputAndRejectsEvidenceOutsideTheApprovedTeam() {
        var source = new PGSimpleDataSource();
        source.setURL(System.getenv("DB_TEST_URL"));
        source.setUser(System.getenv().getOrDefault("DB_USER", "schemaplexai"));
        source.setPassword(System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123"));
        var jdbc = new JdbcTemplate(source);
        String schema = "evidence_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("CREATE SCHEMA " + schema);
        try {
            source.setCurrentSchema(schema);
            jdbc.execute("""
                    CREATE TABLE sf_agent_execution (
                        id UUID, tenant_id UUID, agent_id UUID, parent_execution_id VARCHAR(255),
                        deleted INTEGER, status TEXT, output_result TEXT
                    )
                    """);
            UUID tenant = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            UUID member = UUID.randomUUID();
            String parent = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO sf_agent_execution VALUES (?, ?, ?, ?, 0, 'completed', ?)",
                    member, tenant, agent, parent, "测".repeat(3999) + "📄" + "读".repeat(4_000) + "后续证据");
            var context = AgentExecutionContext.builder().tenantId(tenant.toString()).agentId(agent.toString())
                    .parentExecutionId(parent).teamMemberEvidenceIds(List.of(member.toString())).build();
            var service = new AgentExecutionEvidenceService(mock(DocumentStorageService.class), jdbc);
            var first = service.readMemberEvidencePage(context, member.toString(), 0);
            assertThat(first.content()).endsWith("📄");
            assertThat(first.nextOffset()).isEqualTo(4000);
            var second = service.readMemberEvidencePage(context, member.toString(), first.nextOffset());
            assertThat(second.content()).isEqualTo("读".repeat(4_000));
            assertThat(second.nextOffset()).isEqualTo(8_000);
            assertThat(service.readMemberEvidencePage(context, member.toString(), second.nextOffset()))
                    .isEqualTo(new AgentExecutionEvidenceService.EvidencePage("后续证据", null));
            context.setParentExecutionId(UUID.randomUUID().toString());
            assertThatThrownBy(() -> service.readMemberEvidencePage(context, member.toString(), 0))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            source.setCurrentSchema("public");
            jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
