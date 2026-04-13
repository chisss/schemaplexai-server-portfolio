package com.schemaplexai.service.quality.impl;

import com.schemaplexai.dao.mapper.QualityProfileBindingMapper;
import com.schemaplexai.dao.mapper.QualityProfileMapper;
import com.schemaplexai.dao.mapper.QualityProfileModelMapper;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityProfileBinding;
import com.schemaplexai.model.entity.QualityProfileModel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityProfileResolverServiceImplTest {

    private final QualityProfileMapper qualityProfileMapper = mock(QualityProfileMapper.class);
    private final QualityProfileBindingMapper qualityProfileBindingMapper = mock(QualityProfileBindingMapper.class);
    private final QualityProfileModelMapper qualityProfileModelMapper = mock(QualityProfileModelMapper.class);
    private final QualityProfileResolverServiceImpl service = new QualityProfileResolverServiceImpl(
            qualityProfileMapper, qualityProfileBindingMapper, qualityProfileModelMapper
    );

    @Test
    void shouldRespectSourceTypeWhenResolvingProfile() {
        QualityProfileBinding binding = new QualityProfileBinding();
        binding.setProfileId("profile-manual");
        QualityProfileBinding binding2 = new QualityProfileBinding();
        binding2.setProfileId("profile-workflow");
        when(qualityProfileBindingMapper.selectList(any())).thenReturn(List.of(binding, binding2));

        QualityProfile manualProfile = new QualityProfile();
        manualProfile.setId("profile-manual");
        manualProfile.setStatus("active");
        manualProfile.setIssueType("deviation");
        manualProfile.setTriggerModes(List.of("manual"));

        QualityProfile workflowProfile = new QualityProfile();
        workflowProfile.setId("profile-workflow");
        workflowProfile.setStatus("active");
        workflowProfile.setIssueType("deviation");
        workflowProfile.setTriggerModes(List.of("workflow"));

        when(qualityProfileMapper.selectList(any())).thenReturn(List.of(manualProfile, workflowProfile));

        QualityProfile resolved = service.resolveProfile("spec-1", "wf-1", "agent-1", "deviation", "workflow");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo("profile-workflow");
    }

    @Test
    void shouldPreferSpecificIssueProfileOverDefaultProfileWithinBindings() {
        QualityProfileBinding defaultBinding = new QualityProfileBinding();
        defaultBinding.setProfileId("profile-default");
        QualityProfileBinding specificBinding = new QualityProfileBinding();
        specificBinding.setProfileId("profile-intent");
        when(qualityProfileBindingMapper.selectList(any())).thenReturn(List.of(defaultBinding, specificBinding));

        QualityProfile defaultProfile = new QualityProfile();
        defaultProfile.setId("profile-default");
        defaultProfile.setStatus("active");
        defaultProfile.setIssueType("both");
        defaultProfile.setTriggerModes(List.of("manual", "workflow", "system"));
        defaultProfile.setIsDefault(true);
        defaultProfile.setUpdatedAt(LocalDateTime.of(2026, 4, 8, 12, 0));

        QualityProfile specificProfile = new QualityProfile();
        specificProfile.setId("profile-intent");
        specificProfile.setStatus("active");
        specificProfile.setIssueType("intent_defect");
        specificProfile.setTriggerModes(List.of("manual"));
        specificProfile.setIsDefault(false);
        specificProfile.setUpdatedAt(LocalDateTime.of(2026, 4, 8, 11, 0));

        when(qualityProfileMapper.selectList(any())).thenReturn(List.of(defaultProfile, specificProfile));

        QualityProfile resolved = service.resolveProfile("spec-1", "wf-1", null, "intent_defect", "manual");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo("profile-intent");
    }

    @Test
    void shouldPreferSpecBindingOverWorkflowBinding() {
        QualityProfileBinding specBinding = new QualityProfileBinding();
        specBinding.setProfileId("profile-spec");
        QualityProfileBinding workflowBinding = new QualityProfileBinding();
        workflowBinding.setProfileId("profile-workflow");
        when(qualityProfileBindingMapper.selectList(any()))
                .thenReturn(List.of(specBinding))
                .thenReturn(List.of(workflowBinding))
                .thenReturn(List.of());

        QualityProfile specProfile = new QualityProfile();
        specProfile.setId("profile-spec");
        specProfile.setStatus("active");
        specProfile.setIssueType("deviation");
        specProfile.setTriggerModes(List.of("workflow"));
        specProfile.setUpdatedAt(LocalDateTime.of(2026, 4, 8, 10, 0));

        QualityProfile workflowProfile = new QualityProfile();
        workflowProfile.setId("profile-workflow");
        workflowProfile.setStatus("active");
        workflowProfile.setIssueType("deviation");
        workflowProfile.setTriggerModes(List.of("workflow"));
        workflowProfile.setUpdatedAt(LocalDateTime.of(2026, 4, 8, 12, 0));

        when(qualityProfileMapper.selectList(any())).thenReturn(List.of(workflowProfile, specProfile));

        QualityProfile resolved = service.resolveProfile("spec-1", "wf-1", null, "deviation", "workflow");

        assertThat(resolved).isNotNull();
        assertThat(resolved.getId()).isEqualTo("profile-spec");
    }

    @Test
    void shouldDeduplicateModelIds() {
        QualityProfileModel first = new QualityProfileModel();
        first.setModelId("model-1");
        QualityProfileModel duplicate = new QualityProfileModel();
        duplicate.setModelId("model-1");
        QualityProfileModel second = new QualityProfileModel();
        second.setModelId("model-2");
        when(qualityProfileModelMapper.selectList(any())).thenReturn(List.of(first, duplicate, second));

        List<String> modelIds = service.listModelIds("profile-1");

        assertThat(modelIds).containsExactly("model-1", "model-2");
    }
}
