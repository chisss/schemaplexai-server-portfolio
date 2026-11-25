package com.schemaplexai.service.evaluation;

import com.schemaplexai.model.vo.evaluation.EvalDatasetTemplateVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalDatasetTemplateServiceTest {

    private final EvalDatasetTemplateService service = new EvalDatasetTemplateService();

    @Test
    void shouldExposeTwoBuiltinTemplates() {
        List<EvalDatasetTemplateVO> templates = service.list();

        assertThat(templates).hasSize(2);
        assertThat(templates).extracting(EvalDatasetTemplateVO::getId)
                .containsExactly("rag-qa", "general-qa");
    }

    @Test
    void ragTemplateShouldCoverFiveRagScenariosWithContext() {
        EvalDatasetTemplateVO template = service.findById("rag-qa");

        assertThat(template).isNotNull();
        assertThat(template.getCategory()).isEqualTo("RAG");
        assertThat(template.getSuggestedPassScoreThreshold()).isEqualTo(80);
        assertThat(template.getItems()).hasSize(5);
        // 全部条目带检索上下文（metadata.context），与 EvalMetadataConfig 下放能力配套
        assertThat(template.getItems())
                .allSatisfy(item -> assertThat(item.getMetadata()).containsKey("context"));
        // 场景覆盖：上下文外拒答条目（幻觉控制）
        assertThat(template.getItems().getLast().getInputText()).contains("第四季度");
        assertThat(template.getItems().getLast().getExpectedOutput()).contains("无法回答");
    }

    @Test
    void generalTemplateShouldHaveNoContextMetadata() {
        EvalDatasetTemplateVO template = service.findById("general-qa");

        assertThat(template).isNotNull();
        assertThat(template.getCategory()).isEqualTo("通用");
        assertThat(template.getSuggestedPassScoreThreshold()).isNull();
        assertThat(template.getItems()).hasSize(4);
        assertThat(template.getItems())
                .allSatisfy(item -> assertThat(item.getMetadata()).isNullOrEmpty());
    }

    @Test
    void shouldReturnNullForUnknownTemplate() {
        assertThat(service.findById("not-exist")).isNull();
    }
}
