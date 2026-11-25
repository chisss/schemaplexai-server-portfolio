package com.schemaplexai.service.evaluation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvalMetadataConfigTest {

    @Test
    void shouldReturnEmptyForNullOrEmptyMetadata() {
        assertThat(EvalMetadataConfig.from(null)).isEqualTo(EvalMetadataConfig.EMPTY);
        assertThat(EvalMetadataConfig.from(Map.of())).isEqualTo(EvalMetadataConfig.EMPTY);
        assertThat(EvalMetadataConfig.from(Map.of("unknownKey", "x"))).isEqualTo(EvalMetadataConfig.EMPTY);
    }

    @Test
    void shouldParseSystemPromptAndContext() {
        EvalMetadataConfig config = EvalMetadataConfig.from(Map.of(
                "systemPrompt", "你是评估专用助手",
                "context", "检索结果: 上海今天晴"));

        assertThat(config.hasSystemPrompt()).isTrue();
        assertThat(config.systemPrompt()).isEqualTo("你是评估专用助手");
        assertThat(config.hasContext()).isTrue();
        assertThat(config.contexts()).containsExactly("检索结果: 上海今天晴");
    }

    @Test
    void shouldParseContextListAndIgnoreInvalidEntries() {
        EvalMetadataConfig config = EvalMetadataConfig.from(Map.of(
                "context", List.of("片段A", "", 123, "片段B")));

        assertThat(config.contexts()).containsExactly("片段A", "片段B");
    }

    @Test
    void shouldRenderContextBlockWithPrefix() {
        EvalMetadataConfig config = EvalMetadataConfig.from(Map.of(
                "context", List.of("片段A", "片段B")));

        assertThat(config.renderContextBlock())
                .startsWith("【检索上下文】")
                .contains("片段A")
                .contains("片段B");
    }

    @Test
    void shouldIgnoreBlankSystemPrompt() {
        assertThat(EvalMetadataConfig.from(Map.of("systemPrompt", "  ")).hasSystemPrompt()).isFalse();
    }
}
