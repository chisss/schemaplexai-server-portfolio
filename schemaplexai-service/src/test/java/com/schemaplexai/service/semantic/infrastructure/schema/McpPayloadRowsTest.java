package com.schemaplexai.service.semantic.infrastructure.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class McpPayloadRowsTest {

    private final McpPayloadRows payloadRows = new McpPayloadRows(new ObjectMapper());

    @Test
    void extractsRowsFromTextContentEnvelope() {
        Map<String, Object> payload = Map.of(
                "content", List.of(Map.of("type", "text", "text", "{\"rows\":[{\"name\":\"orders\"}]}")));

        assertThat(payloadRows.extract(payload))
                .containsExactly(Map.of("name", "orders"));
    }

    @Test
    void returnsEmptyRowsForNonStructuredText() {
        Map<String, Object> payload = Map.of("content", List.of(Map.of("type", "text", "text", "ok")));

        assertThat(payloadRows.extract(payload)).isEmpty();
    }
}
