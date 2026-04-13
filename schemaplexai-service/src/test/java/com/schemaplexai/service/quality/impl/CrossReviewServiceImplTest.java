package com.schemaplexai.service.quality.impl;

import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.ArtifactMapper;
import com.schemaplexai.dao.mapper.CrossReviewMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.converter.CrossReviewConverter;
import com.schemaplexai.model.dto.quality.CrossReviewCreateRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.Artifact;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.vo.quality.CrossReviewVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrossReviewServiceImplTest {

    private final CrossReviewMapper crossReviewMapper = mock(CrossReviewMapper.class);
    private final CrossReviewConverter crossReviewConverter = mock(CrossReviewConverter.class);
    private final EntityValidator entityValidator = mock(EntityValidator.class);
    private final QualityCrossReviewExecutionService qualityCrossReviewExecutionService = mock(QualityCrossReviewExecutionService.class);
    private final QualityProfileResolverService qualityProfileResolverService = mock(QualityProfileResolverService.class);
    private final SpecMapper specMapper = mock(SpecMapper.class);
    private final AiModelMapper aiModelMapper = mock(AiModelMapper.class);
    private final ArtifactMapper artifactMapper = mock(ArtifactMapper.class);

    private final CrossReviewServiceImpl service = new CrossReviewServiceImpl(
            crossReviewMapper,
            crossReviewConverter,
            entityValidator,
            qualityCrossReviewExecutionService,
            qualityProfileResolverService,
            specMapper,
            aiModelMapper,
            artifactMapper
    );

    @Test
    void shouldUsePrimaryArtifactContentWhenManualCrossReviewTargetContentMissing() {
        CrossReviewCreateRequest request = new CrossReviewCreateRequest();
        request.setSpecId("spec-1");
        request.setIssueType("both");
        request.setModelIds(List.of("model-a"));

        Spec spec = new Spec();
        spec.setId("spec-1");
        spec.setName("菲律宾信贷系统营销文案");
        spec.setTenantId("tenant-1");
        spec.setPrimaryArtifactId("artifact-1");
        when(specMapper.selectById("spec-1")).thenReturn(spec);

        Artifact artifact = new Artifact();
        artifact.setId("artifact-1");
        artifact.setContentText("## 最终交付物\n\n这里是干净的营销文案终稿");
        when(artifactMapper.selectById("artifact-1")).thenReturn(artifact);

        CrossReview entity = new CrossReview();
        entity.setId("review-1");
        entity.setSpecId("spec-1");
        entity.setModelAId("model-a");
        entity.setStatus("succeeded");
        when(qualityCrossReviewExecutionService.createAndExecute(
                eq("spec-1"),
                isNull(),
                isNull(),
                eq("both"),
                eq(List.of("model-a")),
                eq("manual"),
                isNull(),
                eq("## 最终交付物\n\n这里是干净的营销文案终稿"),
                eq("tenant-1")
        )).thenReturn(entity);

        CrossReviewVO vo = new CrossReviewVO();
        vo.setId("review-1");
        vo.setSpecId("spec-1");
        vo.setModelAId("model-a");
        when(crossReviewConverter.toVO(entity)).thenReturn(vo);

        AiModel model = new AiModel();
        model.setId("model-a");
        model.setName("模型A");
        when(aiModelMapper.selectBatchIds(List.of("model-a"))).thenReturn(List.of(model));

        CrossReviewVO result = service.create(request);

        assertThat(result.getId()).isEqualTo("review-1");
        verify(artifactMapper).selectById("artifact-1");
        verify(qualityCrossReviewExecutionService).createAndExecute(
                eq("spec-1"),
                isNull(),
                isNull(),
                eq("both"),
                eq(List.of("model-a")),
                eq("manual"),
                isNull(),
                eq("## 最终交付物\n\n这里是干净的营销文案终稿"),
                eq("tenant-1")
        );
    }

    @Test
    void shouldPreferBundleVariantContentForMarketingArtifactCrossReview() {
        CrossReviewCreateRequest request = new CrossReviewCreateRequest();
        request.setSpecId("spec-2");
        request.setIssueType("both");
        request.setModelIds(List.of("model-a"));

        Spec spec = new Spec();
        spec.setId("spec-2");
        spec.setName("营销文案回归");
        spec.setTenantId("tenant-2");
        spec.setPrimaryArtifactId("artifact-marketing");
        when(specMapper.selectById("spec-2")).thenReturn(spec);

        Artifact artifact = new Artifact();
        artifact.setId("artifact-marketing");
        artifact.setArtifactType("marketing_copy_bundle");
        artifact.setContentText("# README\n\n这里只是包摘要");
        Map<String, Object> bundleItem = new LinkedHashMap<>();
        bundleItem.put("variantKey", "A");
        bundleItem.put("content", "# 营销终稿\n\n这是用户最终需要审查的文案。");
        artifact.setMetadataJson(Map.of("bundleItems", List.of(bundleItem)));
        when(artifactMapper.selectById("artifact-marketing")).thenReturn(artifact);

        CrossReview entity = new CrossReview();
        entity.setId("review-2");
        entity.setSpecId("spec-2");
        entity.setModelAId("model-a");
        entity.setStatus("succeeded");
        when(qualityCrossReviewExecutionService.createAndExecute(
                eq("spec-2"),
                isNull(),
                isNull(),
                eq("both"),
                eq(List.of("model-a")),
                eq("manual"),
                isNull(),
                eq("# 营销终稿\n\n这是用户最终需要审查的文案。"),
                eq("tenant-2")
        )).thenReturn(entity);

        CrossReviewVO vo = new CrossReviewVO();
        vo.setId("review-2");
        vo.setSpecId("spec-2");
        vo.setModelAId("model-a");
        when(crossReviewConverter.toVO(entity)).thenReturn(vo);

        AiModel model = new AiModel();
        model.setId("model-a");
        model.setName("模型A");
        when(aiModelMapper.selectBatchIds(List.of("model-a"))).thenReturn(List.of(model));

        CrossReviewVO result = service.create(request);

        assertThat(result.getId()).isEqualTo("review-2");
        verify(qualityCrossReviewExecutionService).createAndExecute(
                eq("spec-2"),
                isNull(),
                isNull(),
                eq("both"),
                eq(List.of("model-a")),
                eq("manual"),
                isNull(),
                eq("# 营销终稿\n\n这是用户最终需要审查的文案。"),
                eq("tenant-2")
        );
    }
}
