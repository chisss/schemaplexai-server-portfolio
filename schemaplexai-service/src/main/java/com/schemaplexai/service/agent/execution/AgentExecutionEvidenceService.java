package com.schemaplexai.service.agent.execution;

import com.schemaplexai.service.storage.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.PushbackReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 当前执行的模型可按需读取产物，Team Leader 可按引用读取同队成员的交付。 */
@Service
@RequiredArgsConstructor
public class AgentExecutionEvidenceService {
    public static final String ARTIFACT_ATTRIBUTE = "agentArtifactId";
    private static final int MAX_SLICE_CHARS = 4_000;
    private static final int MAX_ARTIFACT_BYTES = 32 * 1024 * 1024;
    private final DocumentStorageService storage;
    private final JdbcTemplate jdbc;

    public String store(String tenantId, String executionId, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_ARTIFACT_BYTES) {
            throw new IllegalArgumentException("工具产物超过存储上限");
        }
        String id = UUID.randomUUID().toString();
        storage.putObject(storage.getDefaultBucket(), path(tenantId, executionId, id),
                new ByteArrayInputStream(bytes), bytes.length, "text/plain; charset=utf-8");
        return id;
    }

    public String readArtifact(String tenantId, String executionId, String artifactId, int offset) throws IOException {
        return readArtifactPage(tenantId, executionId, artifactId, offset).content();
    }

    public EvidencePage readArtifactPage(String tenantId, String executionId, String artifactId, int offset) throws IOException {
        if (offset < 0 || offset >= MAX_ARTIFACT_BYTES) throw new IllegalArgumentException("偏移超出范围");
        try (var reader = new PushbackReader(new BufferedReader(new InputStreamReader(
                storage.getObject(storage.getDefaultBucket(), path(tenantId, executionId, artifactId)),
                StandardCharsets.UTF_8)))) {
            for (int skipped = 0; skipped < offset; skipped++) {
                if (readCodePoint(reader) < 0) return new EvidencePage("", null);
            }
            StringBuilder content = new StringBuilder(MAX_SLICE_CHARS);
            for (int count = 0; count < MAX_SLICE_CHARS; count++) {
                int codePoint = readCodePoint(reader);
                if (codePoint < 0) return new EvidencePage(content.toString(), null);
                content.appendCodePoint(codePoint);
            }
            Integer next = reader.read() < 0 ? null : offset + MAX_SLICE_CHARS;
            return new EvidencePage(content.toString(), next);
        }
    }

    private int readCodePoint(PushbackReader reader) throws IOException {
        int first = reader.read();
        if (!Character.isHighSurrogate((char) first)) return first;
        int second = reader.read();
        if (Character.isLowSurrogate((char) second)) return Character.toCodePoint((char) first, (char) second);
        if (second >= 0) reader.unread(second);
        return first;
    }

    public EvidencePage readMemberEvidencePage(AgentExecutionContext context, String childId, int offset) {
        if (offset < 0 || offset == Integer.MAX_VALUE || context.getTeamMemberEvidenceIds() == null
                || !context.getTeamMemberEvidenceIds().contains(childId)) {
            throw new IllegalArgumentException("成员交付引用不可用");
        }
        String slice = jdbc.query("""
                SELECT COALESCE(substring(output_result FROM ? FOR ?), '')
                  FROM sf_agent_execution
                 WHERE tenant_id = ?::uuid AND agent_id = ?::uuid AND parent_execution_id = ?
                   AND id = ?::uuid AND deleted = 0 AND status IN ('completed', 'completed_with_fallback')
                """, (row, index) -> row.getString(1), offset + 1, MAX_SLICE_CHARS + 1,
                context.getTenantId(), context.getAgentId(), context.getParentExecutionId(),
                UUID.fromString(childId).toString()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("成员交付引用不可用"));
        int length = slice.codePointCount(0, slice.length());
        Integer next = length > MAX_SLICE_CHARS ? Math.addExact(offset, MAX_SLICE_CHARS) : null;
        return new EvidencePage(next == null ? slice : slice.substring(0, slice.offsetByCodePoints(0, MAX_SLICE_CHARS)), next);
    }

    public record EvidencePage(String content, Integer nextOffset) { }

    private String path(String tenantId, String executionId, String artifactId) {
        return "tenants/" + UUID.fromString(tenantId) + "/agent-executions/"
                + UUID.fromString(executionId) + "/" + UUID.fromString(artifactId) + ".txt";
    }
}
