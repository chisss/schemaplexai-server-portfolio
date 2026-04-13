package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.dao.mapper.QualityProfileBindingMapper;
import com.schemaplexai.dao.mapper.QualityProfileMapper;
import com.schemaplexai.dao.mapper.QualityProfileModelMapper;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityProfileBinding;
import com.schemaplexai.model.entity.QualityProfileModel;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 质量配置组运行时解析服务实现
 */
@Service
@RequiredArgsConstructor
public class QualityProfileResolverServiceImpl implements QualityProfileResolverService {

    private final QualityProfileMapper qualityProfileMapper;
    private final QualityProfileBindingMapper qualityProfileBindingMapper;
    private final QualityProfileModelMapper qualityProfileModelMapper;

    @Override
    public QualityProfile resolveProfile(String specId, String workflowTemplateId, String agentId, String issueType, String sourceType) {
        Map<String, Integer> bindingPriority = new HashMap<>();
        collectBoundProfiles(bindingPriority, "spec", specId, 3);
        collectBoundProfiles(bindingPriority, "workflow", workflowTemplateId, 2);
        collectBoundProfiles(bindingPriority, "agent", agentId, 1);

        if (!bindingPriority.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>(bindingPriority.keySet());
            return qualityProfileMapper.selectList(new LambdaQueryWrapper<QualityProfile>()
                            .in(QualityProfile::getId, ids)
                            .eq(QualityProfile::getStatus, "active")
                            .orderByDesc(QualityProfile::getUpdatedAt))
                    .stream()
                    .filter(profile -> matchesIssueType(profile.getIssueType(), issueType))
                    .filter(profile -> matchesSourceType(profile.getTriggerModes(), sourceType))
                    .sorted(profileComparator(bindingPriority, issueType, sourceType))
                    .findFirst()
                    .orElse(null);
        }

        return qualityProfileMapper.selectList(new LambdaQueryWrapper<QualityProfile>()
                        .eq(QualityProfile::getStatus, "active")
                        .orderByDesc(QualityProfile::getUpdatedAt))
                .stream()
                .filter(profile -> matchesIssueType(profile.getIssueType(), issueType))
                .filter(profile -> matchesSourceType(profile.getTriggerModes(), sourceType))
                .sorted(profileComparator(Map.of(), issueType, sourceType))
                .findFirst()
                .orElse(null);
    }

    @Override
    public List<String> listModelIds(String profileId) {
        if (!StringUtils.hasText(profileId)) {
            return List.of();
        }
        return qualityProfileModelMapper.selectList(new LambdaQueryWrapper<QualityProfileModel>()
                        .eq(QualityProfileModel::getProfileId, profileId)
                        .orderByAsc(QualityProfileModel::getSortOrder))
                .stream()
                .map(QualityProfileModel::getModelId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private Comparator<QualityProfile> profileComparator(Map<String, Integer> bindingPriority,
                                                         String issueType,
                                                         String sourceType) {
        return Comparator
                .comparingInt((QualityProfile profile) -> bindingPriority.getOrDefault(profile.getId(), 0))
                .reversed()
                .thenComparing(Comparator.comparingInt(
                        (QualityProfile profile) -> issueMatchScore(profile.getIssueType(), issueType)
                ).reversed())
                .thenComparing(Comparator.comparingInt(
                        (QualityProfile profile) -> sourceMatchScore(profile.getTriggerModes(), sourceType)
                ).reversed())
                .thenComparing(Comparator.comparingInt(
                        (QualityProfile profile) -> Boolean.TRUE.equals(profile.getIsDefault()) ? 0 : 1
                ).reversed())
                .thenComparing(QualityProfile::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(QualityProfile::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private void collectBoundProfiles(Map<String, Integer> collector, String bindingType, String bindingId, int priority) {
        if (!StringUtils.hasText(bindingId)) {
            return;
        }
        qualityProfileBindingMapper.selectList(new LambdaQueryWrapper<QualityProfileBinding>()
                        .eq(QualityProfileBinding::getBindingType, bindingType)
                        .eq(QualityProfileBinding::getBindingId, bindingId))
                .stream()
                .map(QualityProfileBinding::getProfileId)
                .filter(StringUtils::hasText)
                .forEach(profileId -> collector.merge(profileId, priority, Math::max));
    }

    private boolean matchesIssueType(String profileIssueType, String issueType) {
        if (!StringUtils.hasText(issueType) || !StringUtils.hasText(profileIssueType)) {
            return true;
        }
        return "both".equalsIgnoreCase(profileIssueType) || profileIssueType.equalsIgnoreCase(issueType);
    }

    private boolean matchesSourceType(List<String> triggerModes, String sourceType) {
        if (!StringUtils.hasText(sourceType) || triggerModes == null || triggerModes.isEmpty()) {
            return true;
        }
        return triggerModes.stream().anyMatch(mode -> sourceType.equalsIgnoreCase(mode));
    }

    private int issueMatchScore(String profileIssueType, String issueType) {
        if (!StringUtils.hasText(issueType) || !StringUtils.hasText(profileIssueType)) {
            return 0;
        }
        if (profileIssueType.equalsIgnoreCase(issueType)) {
            return 2;
        }
        return "both".equalsIgnoreCase(profileIssueType) ? 1 : 0;
    }

    private int sourceMatchScore(List<String> triggerModes, String sourceType) {
        if (!StringUtils.hasText(sourceType) || triggerModes == null || triggerModes.isEmpty()) {
            return 0;
        }
        if (!matchesSourceType(triggerModes, sourceType)) {
            return 0;
        }
        return triggerModes.size() == 1 ? 2 : 1;
    }
}
