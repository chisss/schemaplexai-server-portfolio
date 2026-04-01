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
import java.util.LinkedHashSet;
import java.util.List;
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
    public QualityProfile resolveProfile(String specId, String workflowTemplateId, String agentId, String issueType) {
        List<String> candidateProfileIds = new ArrayList<>();
        collectBoundProfiles(candidateProfileIds, "spec", specId);
        collectBoundProfiles(candidateProfileIds, "workflow", workflowTemplateId);
        collectBoundProfiles(candidateProfileIds, "agent", agentId);

        if (!candidateProfileIds.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>(candidateProfileIds);
            return qualityProfileMapper.selectList(new LambdaQueryWrapper<QualityProfile>()
                            .in(QualityProfile::getId, ids)
                            .eq(QualityProfile::getStatus, "active")
                            .orderByDesc(QualityProfile::getIsDefault)
                            .orderByDesc(QualityProfile::getUpdatedAt))
                    .stream()
                    .filter(profile -> matchesIssueType(profile.getIssueType(), issueType))
                    .findFirst()
                    .orElse(null);
        }

        return qualityProfileMapper.selectList(new LambdaQueryWrapper<QualityProfile>()
                        .eq(QualityProfile::getStatus, "active")
                        .orderByDesc(QualityProfile::getIsDefault)
                        .orderByDesc(QualityProfile::getUpdatedAt))
                .stream()
                .filter(profile -> matchesIssueType(profile.getIssueType(), issueType))
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
                .toList();
    }

    private void collectBoundProfiles(List<String> collector, String bindingType, String bindingId) {
        if (!StringUtils.hasText(bindingId)) {
            return;
        }
        collector.addAll(qualityProfileBindingMapper.selectList(new LambdaQueryWrapper<QualityProfileBinding>()
                        .eq(QualityProfileBinding::getBindingType, bindingType)
                        .eq(QualityProfileBinding::getBindingId, bindingId))
                .stream()
                .map(QualityProfileBinding::getProfileId)
                .toList());
    }

    private boolean matchesIssueType(String profileIssueType, String issueType) {
        if (!StringUtils.hasText(issueType) || !StringUtils.hasText(profileIssueType)) {
            return true;
        }
        return "both".equalsIgnoreCase(profileIssueType) || profileIssueType.equalsIgnoreCase(issueType);
    }
}
