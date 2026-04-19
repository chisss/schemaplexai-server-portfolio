package com.schemaplexai.service.vector.impl;

import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OnnxScoringServiceImplTest {

    @Test
    void shouldFallbackToOriginalOrderWhenRerankerFiltersAllPassages() throws Exception {
        OnnxScoringServiceImpl service = new OnnxScoringServiceImpl();
        OnnxScoringModel scoringModel = mock(OnnxScoringModel.class);
        when(scoringModel.score("query", "passage-a")).thenReturn(Response.from(-0.4));
        when(scoringModel.score("query", "passage-b")).thenReturn(Response.from(-0.2));

        setField(service, "scoringModel", scoringModel);
        setField(service, "initAttempted", true);
        setField(service, "minScore", 0.0D);

        List<com.schemaplexai.service.vector.ScoringService.ScoredPassage> result = service.rerank(
                "query",
                List.of("passage-a", "passage-b"),
                2
        );

        assertThat(result).extracting(com.schemaplexai.service.vector.ScoringService.ScoredPassage::text)
                .containsExactly("passage-a", "passage-b");
        assertThat(result).allMatch(item -> item.score() == 0.0D);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = OnnxScoringServiceImpl.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
