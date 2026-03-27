package com.schemaplexai.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolDefinition → LangChain4j ToolSpecification 转换器
 *
 * <p>将从 {@link com.schemaplexai.service.agent.tool.ToolRegistry} 加载的工具定义
 * 转换为 LangChain4j 低层 API 所需的 {@link ToolSpecification}，
 * 用于 {@link dev.langchain4j.model.chat.request.ChatRequest} 的 toolSpecifications 字段。
 */
@Slf4j
@Component
public class LangChain4jToolSpecProvider {

    public List<ToolSpecification> toToolSpecifications(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return List.of();
        }
        return tools.stream()
                .map(this::toToolSpecification)
                .toList();
    }

    private ToolSpecification toToolSpecification(ToolDefinition tool) {
        return ToolSpecification.builder()
                .name(tool.getCode())
                .description(tool.getDescription())
                .parameters(buildParameters(tool.getInputSchema()))
                .build();
    }

    private JsonObjectSchema buildParameters(JsonNode inputSchema) {
        if (inputSchema == null || inputSchema.isNull() || inputSchema.isEmpty()) {
            return JsonObjectSchema.builder().build();
        }

        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();

        JsonNode properties = inputSchema.path("properties");
        if (properties.isObject()) {
            Map<String, JsonSchemaElement> props = new LinkedHashMap<>();
            properties.fields().forEachRemaining(entry ->
                    props.put(entry.getKey(), toJsonSchemaElement(entry.getValue())));
            builder.addProperties(props);
        }

        JsonNode required = inputSchema.path("required");
        if (required.isArray()) {
            List<String> requiredList = new ArrayList<>();
            required.forEach(node -> requiredList.add(node.asText()));
            builder.required(requiredList.toArray(new String[0]));
        }

        return builder.build();
    }

    private JsonSchemaElement toJsonSchemaElement(JsonNode propertySchema) {
        String type = propertySchema.path("type").asText("string");
        String description = propertySchema.path("description").asText(null);
        return switch (type) {
            case "integer" -> {
                var b = JsonIntegerSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "number" -> {
                var b = JsonNumberSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "boolean" -> {
                var b = JsonBooleanSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "array" -> {
                var b = JsonArraySchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            case "object" -> {
                var b = JsonObjectSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
            default -> {
                var b = JsonStringSchema.builder();
                if (description != null) b.description(description);
                yield b.build();
            }
        };
    }
}
