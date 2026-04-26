package com.schemaplexai.service.config.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AiModelGroupItemMapper;
import com.schemaplexai.dao.mapper.AiModelGroupMapper;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.model.dto.system.AiModelGroupAutoGenerateRequest;
import com.schemaplexai.model.dto.system.AiModelGroupCreateRequest;
import com.schemaplexai.model.dto.system.AiModelGroupItemSaveRequest;
import com.schemaplexai.model.dto.system.AiModelGroupUpdateRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.AiModelGroup;
import com.schemaplexai.model.entity.AiModelGroupItem;
import com.schemaplexai.model.vo.system.AiModelGroupVO;
import com.schemaplexai.service.ai.TokenEstimator;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.config.AiModelGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI模型组管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelGroupServiceImpl implements AiModelGroupService {

    private final AiModelGroupMapper groupMapper;
    private final AiModelGroupItemMapper itemMapper;
    private final AiModelMapper aiModelMapper;
    private final EntityValidator entityValidator;
    private final TokenEstimator tokenEstimator;

    @Override
    public List<AiModelGroupVO> listByCurrentTenant() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        var groups = groupMapper.selectList(new LambdaQueryWrapper<AiModelGroup>()
                .eq(AiModelGroup::getTenantId, tenantId)
                .orderByDesc(AiModelGroup::getCreatedAt));
        return groups.stream().map(this::enrichWithItems).collect(Collectors.toList());
    }

    @Override
    public AiModelGroupVO getById(String id) {
        var group = requireGroup(id);
        return enrichWithItems(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelGroupVO create(AiModelGroupCreateRequest request) {
        var group = new AiModelGroup();
        group.setName(request.getName());
        group.setDescription(request.getDescription());
        group.setRoutingStrategy(StringUtils.hasText(request.getRoutingStrategy())
                ? request.getRoutingStrategy() : "manual");
        group.setStatus(CommonConstant.STATUS_ACTIVE);
        groupMapper.insert(group);
        log.info("创建模型组成功: id={}, name={}", group.getId(), group.getName());
        return enrichWithItems(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelGroupVO update(String id, AiModelGroupUpdateRequest request) {
        var group = requireGroup(id);
        if (StringUtils.hasText(request.getName())) group.setName(request.getName());
        if (StringUtils.hasText(request.getDescription())) group.setDescription(request.getDescription());
        if (StringUtils.hasText(request.getRoutingStrategy())) group.setRoutingStrategy(request.getRoutingStrategy());
        if (StringUtils.hasText(request.getStatus())) group.setStatus(request.getStatus());
        groupMapper.updateById(group);
        return enrichWithItems(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        requireGroup(id);
        itemMapper.delete(new LambdaQueryWrapper<AiModelGroupItem>()
                .eq(AiModelGroupItem::getGroupId, id));
        groupMapper.deleteById(id);
        log.info("删除模型组: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelGroupVO saveItems(String groupId, AiModelGroupItemSaveRequest request) {
        var group = requireGroup(groupId);
        List<AiModel> selectedModels = validateSameUseCaseModels(request.getModelIds());
        // 整体替换成员列表
        itemMapper.delete(new LambdaQueryWrapper<AiModelGroupItem>()
                .eq(AiModelGroupItem::getGroupId, groupId));

        if (!CollectionUtils.isEmpty(request.getModelIds())) {
            for (int i = 0; i < request.getModelIds().size(); i++) {
                var item = new AiModelGroupItem();
                item.setGroupId(groupId);
                item.setModelId(request.getModelIds().get(i));
                item.setSortOrder(i);
                item.setCreatedAt(LocalDateTime.now());
                itemMapper.insert(item);
            }
        }
        log.info("保存模型组成员: groupId={}, count={}, useCase={}", groupId,
                CollectionUtils.isEmpty(request.getModelIds()) ? 0 : request.getModelIds().size(),
                CollectionUtils.isEmpty(selectedModels) ? "-" : selectedModels.getFirst().getUseCase());
        return enrichWithItems(group);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiModelGroupVO autoGenerate(AiModelGroupAutoGenerateRequest request) {
        if (!StringUtils.hasText(request.getUseCase())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "自动生成模型组时必须指定 useCase");
        }
        String tenantId = SecurityUtil.getCurrentTenantId();
        var activeModels = aiModelMapper.selectList(new LambdaQueryWrapper<AiModel>()
                .eq(AiModel::getTenantId, tenantId)
                .eq(AiModel::getStatus, CommonConstant.STATUS_ACTIVE)
                .apply(StringUtils.hasText(request.getUseCase()), "lower(use_case) = lower({0})", request.getUseCase()));

        if (CollectionUtils.isEmpty(activeModels)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前租户没有可用的激活模型");
        }

        List<AiModel> sorted = sortModels(activeModels, request.getStrategy());

        // 生成组名
        String groupName = StringUtils.hasText(request.getName())
                ? request.getName()
                : buildAutoGroupName(request.getStrategy());

        // 创建模型组
        var group = new AiModelGroup();
        group.setName(groupName);
        group.setDescription("按" + ("by_price".equals(request.getStrategy()) ? "价格" : "性能") + "自动生成"
                + (StringUtils.hasText(request.getUseCase()) ? "（" + request.getUseCase() + "）" : ""));
        group.setRoutingStrategy(request.getStrategy());
        group.setStatus(CommonConstant.STATUS_ACTIVE);
        groupMapper.insert(group);

        // 保存成员
        for (int i = 0; i < sorted.size(); i++) {
            var item = new AiModelGroupItem();
            item.setGroupId(group.getId());
            item.setModelId(sorted.get(i).getId());
            item.setSortOrder(i);
            item.setCreatedAt(LocalDateTime.now());
            itemMapper.insert(item);
        }

        log.info("自动生成模型组: strategy={}, groupId={}, modelCount={}", request.getStrategy(), group.getId(), sorted.size());
        return enrichWithItems(group);
    }

    private List<AiModel> sortModels(List<AiModel> models, String strategy) {
        return switch (strategy) {
            case "by_price" -> models.stream()
                    .sorted(Comparator.comparing(m -> tokenEstimator.estimateTaskCost(m, m.getUseCase())))
                    .collect(Collectors.toList());
            case "by_performance" -> models.stream()
                    .filter(m -> m.getLastTestLatency() != null)
                    .sorted(Comparator.comparingInt(AiModel::getLastTestLatency))
                    .collect(Collectors.toList());
            default -> new ArrayList<>(models);
        };
    }

    private String buildAutoGroupName(String strategy) {
        return "by_price".equals(strategy) ? "按价格优先组" : "按性能优先组";
    }

    private AiModelGroup requireGroup(String id) {
        var group = groupMapper.selectById(id);
        if (group == null) {
            throw new BusinessException(ResultCode.MODEL_GROUP_NOT_FOUND);
        }
        // 校验租户归属
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (StringUtils.hasText(tenantId) && !tenantId.equals(group.getTenantId())) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return group;
    }

    private AiModelGroupVO enrichWithItems(AiModelGroup group) {
        var vo = new AiModelGroupVO();
        vo.setId(group.getId());
        vo.setName(group.getName());
        vo.setDescription(group.getDescription());
        vo.setRoutingStrategy(group.getRoutingStrategy());
        vo.setStatus(group.getStatus());
        vo.setCreatedAt(group.getCreatedAt());
        vo.setUpdatedAt(group.getUpdatedAt());

        // 查询成员并关联模型名称
        var items = itemMapper.selectList(new LambdaQueryWrapper<AiModelGroupItem>()
                .eq(AiModelGroupItem::getGroupId, group.getId())
                .orderByAsc(AiModelGroupItem::getSortOrder));

        if (!CollectionUtils.isEmpty(items)) {
            var modelIds = items.stream().map(AiModelGroupItem::getModelId).collect(Collectors.toList());
            var models = aiModelMapper.selectBatchIds(modelIds);
            Map<String, AiModel> modelMap = models.stream()
                    .collect(Collectors.toMap(AiModel::getId, Function.identity()));

            var itemVOs = items.stream().map(item -> {
                var itemVO = new AiModelGroupVO.AiModelGroupItemVO();
                itemVO.setId(item.getId());
                itemVO.setModelId(item.getModelId());
                itemVO.setSortOrder(item.getSortOrder());
                AiModel model = modelMap.get(item.getModelId());
                if (model != null) {
                    itemVO.setModelName(model.getName());
                    itemVO.setProvider(model.getProvider());
                    itemVO.setUseCase(model.getUseCase());
                }
                return itemVO;
            }).collect(Collectors.toList());
            vo.setItems(itemVOs);
            vo.setUseCase(itemVOs.getFirst().getUseCase());
        } else {
            vo.setItems(new ArrayList<>());
        }

        return vo;
    }

    private List<AiModel> validateSameUseCaseModels(List<String> modelIds) {
        if (CollectionUtils.isEmpty(modelIds)) {
            return List.of();
        }
        List<AiModel> models = aiModelMapper.selectBatchIds(modelIds);
        if (models.size() != modelIds.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "存在不存在的模型配置");
        }
        List<String> useCases = models.stream()
                .map(AiModel::getUseCase)
                .filter(StringUtils::hasText)
                .map(String::toLowerCase)
                .distinct()
                .toList();
        if (useCases.size() > 1) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "模型组仅允许配置同一 useCase 的模型");
        }
        return models;
    }
}
