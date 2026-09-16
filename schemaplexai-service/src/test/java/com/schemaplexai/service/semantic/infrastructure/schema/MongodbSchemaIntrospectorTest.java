package com.schemaplexai.service.semantic.infrastructure.schema;

import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MongodbSchemaIntrospectorTest {

    @Test
    void keepsOnlyTypesAndNeverPersistsSampleValues() {
        StubSchemaMetadataSession session = new StubSchemaMetadataSession(
                "mongodb", "sales", statement -> List.of(), this::invoke);

        var schema = new MongodbSchemaIntrospector().scan(SchemaScanScope.all(), session);

        var customers = schema.objects().getFirst();
        assertThat(customers.fields()).singleElement().satisfies(field -> {
            assertThat(field.name()).isEqualTo("email");
            assertThat(field.typeDistribution()).containsEntry("string", 98L).containsEntry("null", 2L);
        });
        assertThat(customers.indexes()).extracting("name").containsExactly("email_1");
        assertThat(schema.toString()).doesNotContain("alice@example.com", "4111111111111111");
    }

    private List<Map<String, Object>> invoke(String operation, Map<String, Object> arguments) {
        return switch (operation) {
            case "list_collections" -> List.of(Map.of(
                    "name", "customers",
                    "sample", Map.of("email", "alice@example.com")));
            case "describe_collection" -> List.of(Map.of(
                    "field_name", "email",
                    "data_type", "string",
                    "nullable", true,
                    "type_distribution", Map.of("string", 98, "null", 2),
                    "sample_value", "alice@example.com",
                    "credit_card", "4111111111111111"));
            case "list_indexes" -> {
                assertThat(arguments).doesNotContainKey("sampleLimit");
                yield List.of(Map.of(
                        "name", "email_1", "fields", List.of("email"), "unique", true, "type", "btree"));
            }
            default -> List.of();
        };
    }
}
