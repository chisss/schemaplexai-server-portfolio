package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.QualityProfileBindingMapper;
import com.schemaplexai.dao.mapper.QualityProfileMapper;
import com.schemaplexai.dao.mapper.QualityProfileModelMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.dto.quality.QualityProfileBindingRequest;
import com.schemaplexai.model.dto.quality.QualityProfileConfigRequest;
import com.schemaplexai.model.entity.Agent;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.QualityProfile;
import com.schemaplexai.model.entity.QualityProfileBinding;
import com.schemaplexai.model.entity.QualityProfileModel;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.quality.QualityProfileBindingVO;
import com.schemaplexai.model.vo.quality.QualityProfileConfigVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.QualityProfileFacadeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 质量配置组门面服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityProfileFacadeServiceImpl implements QualityProfileFacadeService {

    private final QualityProfileMapper qualityProfileMapper;
    private final QualityProfileBindingMapper qualityProfileBindingMapper;
    private final QualityProfileModelMapper qualityProfileModelMapper;
    private final AiModelMapper aiModelMapper;
    private final AgentMapper agentMapper;
    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final SpecMapper specMapper;
    private final EntityValidator entityValidator;

    @Override
    public List<QualityProfileConfigVO> list() {
        return qualityProfileMapper.selectList(new LambdaQueryWrapper<QualityProfile>()
                        .orderByDesc(QualityProfile::getIsDefault)
                        .orderByDesc(QualityProfile::getUpdatedAt))
                .stream()
                .map(this::buildVO)
                .toList();
    }

    @Override
    public QualityProfileConfigVO getById(String id) {
        QualityProfile profile = entityValidator.requireExists(qualityProfileMapper, id, ResultCode.CONFIG_NOT_FOUND);
        return buildVO(profile);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityProfileConfigVO create(QualityProfileConfigRequest request) {
        validateRequest(request, null);
        QualityProfile profile = new QualityProfile();
        profile.setTenantId(SecurityUtil.getCurrentTenantId());
        profile.setCode(request.getCode());
        profile.setName(request.getName());
        profile.setIssueType(StringUtils.hasText(request.getIssueType()) ? request.getIssueType() : "both");
        profile.setDescription(request.getDescription());
        profile.setTriggerModes(request.getTriggerModes());
        profile.setEnabledDimensionCodes(request.getEnabledDimensionCodes());
        profile.setEnabledRuleCodes(request.getEnabledRuleCodes());
        profile.setThresholdConfig(request.getThresholdConfig());
        profile.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : CommonConstant.STATUS_ACTIVE);
        profile.setIsDefault(Boolean.TRUE.equals(request.getIsDefault()));
        profile.setIsBuiltin(false);
        profile.setVersion(1);
        qualityProfileMapper.insert(profile);
        saveModels(profile.getId(), request.getModelIds());
        saveBindings(profile.getId(), request.getBindings());
        return getById(profile.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public QualityProfileConfigVO update(String id, QualityProfileConfigRequest request) {
        entityValidator.requireExists(qualityProfileMapper, id, ResultCode.CONFIG_NOT_FOUND);
        validateRequest(request, id);
        QualityProfile patch = new QualityProfile();
        patch.setId(id);
        patch.setCode(request.getCode());
        patch.setName(request.getName());
        patch.setIssueType(StringUtils.hasText(request.getIssueType()) ? request.getIssueType() : "both");
        patch.setDescription(request.getDescription());
        patch.setTriggerModes(request.getTriggerModes());
        patch.setEnabledDimensionCodes(request.getEnabledDimensionCodes());
        patch.setEnabledRuleCodes(request.getEnabledRuleCodes());
        patch.setThresholdConfig(request.getThresholdConfig());
        patch.setStatus(request.getStatus());
        patch.setIsDefault(request.getIsDefault());
        qualityProfileMapper.updateById(patch);
        replaceModels(id, request.getModelIds());
        replaceBindings(id, request.getBindings());
        return getById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(qualityProfileMapper, id, ResultCode.CONFIG_NOT_FOUND);
        qualityProfileBindingMapper.delete(new LambdaQueryWrapper<QualityProfileBinding>()
                .eq(QualityProfileBinding::getProfileId, id));
        qualityProfileModelMapper.delete(new LambdaQueryWrapper<QualityProfileModel>()
                .eq(QualityProfileModel::getProfileId, id));
        qualityProfileMapper.deleteById(id);
    }

    private void validateRequest(QualityProfileConfigRequest request, String excludeId) {
        if (request == null || !StringUtils.hasText(request.getCode()) || !StringUtils.hasText(request.getName())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "质量配置组参数不完整");
        }
        if (request.getModelIds() == null || request.getModelIds().isEmpty() || request.getModelIds().size() > 2) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "质量配置组需要选择1到2个模型");
        }
        Long count = qualityProfileMapper.selectCount(new LambdaQueryWrapper<QualityProfile>()
                .eq(QualityProfile::getCode, request.getCode())
                .ne(StringUtils.hasText(excludeId), QualityProfile::getId, excludeId));
        if (count != null && count > 0) {
            throw new BusinessException(ResultCode.FAIL, "质量配置组编码已存在");
        }
    }

    private void replaceModels(String profileId, List<String> modelIds) {
        qualityProfileModelMapper.delete(new LambdaQueryWrapper<QualityProfileModel>()
                .eq(QualityProfileModel::getProfileId, profileId));
        saveModels(profileId, modelIds);
    }

    private void saveModels(String profileId, List<String> modelIds) {
        if (modelIds == null) {
            return;
        }
        for (int i = 0; i < modelIds.size(); i++) {
            String modelId = modelIds.get(i);
            QualityProfileModel entity = new QualityProfileModel();
            entity.setTenantId(SecurityUtil.getCurrentTenantId());
            entity.setProfileId(profileId);
            entity.setModelId(modelId);
            entity.setModelRole(i == 0 ? "primary" : "reviewer");
            entity.setSortOrder(i + 1);
            qualityProfileModelMapper.insert(entity);
        }
    }

    private void replaceBindings(String profileId, List<QualityProfileBindingRequest> bindings) {
        qualityProfileBindingMapper.delete(new LambdaQueryWrapper<QualityProfileBinding>()
                .eq(QualityProfileBinding::getProfileId, profileId));
        saveBindings(profileId, bindings);
    }

    private void saveBindings(String profileId, List<QualityProfileBindingRequest> bindings) {
        if (bindings == null) {
            return;
        }
        for (QualityProfileBindingRequest binding : bindings) {
            if (binding == null || !StringUtils.hasText(binding.getBindingType()) || !StringUtils.hasText(binding.getBindingId())) {
                continue;
            }
            QualityProfileBinding entity = new QualityProfileBinding();
            entity.setTenantId(SecurityUtil.getCurrentTenantId());
            entity.setProfileId(profileId);
            entity.setBindingType(binding.getBindingType());
            entity.setBindingId(binding.getBindingId());
            qualityProfileBindingMapper.insert(entity);
        }
    }

    private QualityProfileConfigVO buildVO(QualityProfile profile) {
        QualityProfileConfigVO vo = new QualityProfileConfigVO();
        vo.setId(profile.getId());
        vo.setCode(profile.getCode());
        vo.setName(profile.getName());
        vo.setIssueType(profile.getIssueType());
        vo.setDescription(profile.getDescription());
        vo.setTriggerModes(profile.getTriggerModes());
        vo.setEnabledDimensionCodes(profile.getEnabledDimensionCodes());
        vo.setEnabledRuleCodes(profile.getEnabledRuleCodes());
        vo.setThresholdConfig(profile.getThresholdConfig());
        vo.setStatus(profile.getStatus());
        vo.setIsDefault(profile.getIsDefault());
        vo.setVersion(profile.getVersion());
        vo.setCreatedAt(profile.getCreatedAt());
        vo.setUpdatedAt(profile.getUpdatedAt());

        List<QualityProfileModel> profileModels = qualityProfileModelMapper.selectList(new LambdaQueryWrapper<QualityProfileModel>()
                .eq(QualityProfileModel::getProfileId, profile.getId())
                .orderByAsc(QualityProfileModel::getSortOrder));
        List<String> modelIds = profileModels.stream().map(QualityProfileModel::getModelId).toList();
        vo.setModelIds(modelIds);
        Map<String, String> modelNameMap = loadModelNameMap(modelIds);
        vo.setModelNames(modelIds.stream()
                .map(modelNameMap::get)
                .filter(StringUtils::hasText)
                .toList());

        List<QualityProfileBinding> bindings = qualityProfileBindingMapper.selectList(new LambdaQueryWrapper<QualityProfileBinding>()
                .eq(QualityProfileBinding::getProfileId, profile.getId()));
        vo.setBindings(buildBindingVOs(bindings));
        return vo;
    }

    private List<QualityProfileBindingVO> buildBindingVOs(List<QualityProfileBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return List.of();
        }
        Map<String, List<String>> grouped = bindings.stream()
                .collect(Collectors.groupingBy(QualityProfileBinding::getBindingType,
                        Collectors.mapping(QualityProfileBinding::getBindingId, Collectors.toList())));
        Map<String, String> agentNameMap = loadAgentNameMap(grouped.get("agent"));
        Map<String, String> workflowNameMap = loadWorkflowNameMap(grouped.get("workflow"));
        Map<String, String> specNameMap = loadSpecNameMap(grouped.get("spec"));
        List<QualityProfileBindingVO> result = new ArrayList<>();
        for (QualityProfileBinding binding : bindings) {
            QualityProfileBindingVO vo = new QualityProfileBindingVO();
            vo.setId(binding.getId());
            vo.setBindingType(binding.getBindingType());
            vo.setBindingId(binding.getBindingId());
            if ("agent".equalsIgnoreCase(binding.getBindingType())) {
                vo.setBindingName(agentNameMap.get(binding.getBindingId()));
            } else if ("workflow".equalsIgnoreCase(binding.getBindingType())) {
                vo.setBindingName(workflowNameMap.get(binding.getBindingId()));
            } else if ("spec".equalsIgnoreCase(binding.getBindingType())) {
                vo.setBindingName(specNameMap.get(binding.getBindingId()));
            }
            result.add(vo);
        }
        return result;
    }

    private Map<String, String> loadModelNameMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return aiModelMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(AiModel::getId, AiModel::getName, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, String> loadAgentNameMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return agentMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getName, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, String> loadWorkflowNameMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return workflowTemplateMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(WorkflowTemplate::getId, WorkflowTemplate::getName, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, String> loadSpecNameMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return specMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Spec::getId, Spec::getName, (left, right) -> left, LinkedHashMap::new));
    }
}
