package com.schemaplexai.service.ai;

import com.schemaplexai.service.agent.tool.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jToolSpecProviderTest {

    private final LangChain4jToolSpecProvider provider = new LangChain4jToolSpecProvider();

    @Test
    void shouldNormalizeInvalidToolNameForModelCalling() {
        ToolDefinition definition = ToolDefinition.builder()
                .code("sys.read")
                .description("读取文件")
                .build();

        var specification = provider.toToolSpecification(definition);

        assertThat(specification.name()).isEqualTo("sys_read");
        assertThat(specification.metadata()).containsEntry(ToolNameNormalizer.METADATA_ACTUAL_TOOL_NAME, "sys.read");
    }
}
