package com.schemaplexai.service.artifact.impl;

import com.schemaplexai.dao.mapper.ArtifactDeliveryMapper;
import com.schemaplexai.dao.mapper.ArtifactMapper;
import com.schemaplexai.dao.mapper.ArtifactVersionMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.entity.Artifact;
import com.schemaplexai.model.vo.artifact.ArtifactVO;
import com.schemaplexai.service.artifact.ArtifactService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactServiceImplTest {

    private final ArtifactMapper artifactMapper = mock(ArtifactMapper.class);
    private final ArtifactVersionMapper artifactVersionMapper = mock(ArtifactVersionMapper.class);
    private final ArtifactDeliveryMapper artifactDeliveryMapper = mock(ArtifactDeliveryMapper.class);
    private final SpecMapper specMapper = mock(SpecMapper.class);

    private final ArtifactServiceImpl service = new ArtifactServiceImpl(
            artifactMapper,
            artifactVersionMapper,
            artifactDeliveryMapper,
            specMapper
    );

    @Test
    void shouldExposeBundlePrimaryContentInArtifactDetail() {
        Artifact artifact = new Artifact();
        artifact.setId("artifact-1");
        artifact.setArtifactType("marketing_copy_bundle");
        artifact.setTitle("营销文案包");
        artifact.setContentText("# README\n\n这里只是摘要");
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("variantKey", "A");
        variant.put("content", "# 最终交付物\n\n这里是纯净终稿");
        artifact.setMetadataJson(Map.of("bundleItems", List.of(variant)));
        when(artifactMapper.selectById("artifact-1")).thenReturn(artifact);
        when(artifactVersionMapper.selectList(any())).thenReturn(List.of());
        when(artifactDeliveryMapper.selectList(any())).thenReturn(List.of());

        ArtifactVO result = service.getById("artifact-1");

        assertThat(result.getContentText()).isEqualTo("# 最终交付物\n\n这里是纯净终稿");
    }

    @Test
    void shouldDownloadSingleMarketingVariantAsMarkdown() {
        Artifact artifact = new Artifact();
        artifact.setId("artifact-2");
        artifact.setArtifactType("marketing_copy_bundle");
        artifact.setTitle("营销文案包");
        artifact.setContentText("# README\n\n这里只是摘要");
        Map<String, Object> variant = new LinkedHashMap<>();
        variant.put("variantKey", "A");
        variant.put("fileName", "variant-a.md");
        variant.put("content", "# 最终交付物\n\n这里是纯净终稿");
        artifact.setMetadataJson(Map.of(
                "bundleSummaryMarkdown", "# README\n\n这里只是摘要",
                "bundleItems", List.of(variant)
        ));
        when(artifactMapper.selectById("artifact-2")).thenReturn(artifact);

        ArtifactService.DownloadPayload payload = service.download("artifact-2");

        assertThat(payload.fileName()).isEqualTo("variant-a.md");
        assertThat(payload.mimeType()).isEqualTo("text/markdown");
        assertThat(new String(payload.content(), StandardCharsets.UTF_8))
                .isEqualTo("# 最终交付物\n\n这里是纯净终稿");
    }
}
