package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ClickhouseSchemaIntrospectorTest {

    @Test
    void preservesEnginePartitionAndNullableType() {
        StubSchemaMetadataSession session = new StubSchemaMetadataSession(
                "clickhouse", "analytics",
                statement -> List.of(Map.of(
                        "object_schema", "analytics", "object_name", "events",
                        "field_name", "user_id", "data_type", "Nullable(String)",
                        "engine", "MergeTree", "partition_key", "toYYYYMM(event_time)",
                        "primary_key", "user_id,event_time")),
                (operation, arguments) -> List.of());

        var schema = new ClickhouseSchemaIntrospector().scan(SchemaScanScope.all(), session);

        var events = schema.objects().getFirst();
        assertThat(events.engine()).isEqualTo("MergeTree");
        assertThat(events.partitionKey()).isEqualTo("toYYYYMM(event_time)");
        assertThat(events.primaryKey()).containsExactly("user_id", "event_time");
        assertThat(events.fields().getFirst().nullable()).isTrue();
    }
}
