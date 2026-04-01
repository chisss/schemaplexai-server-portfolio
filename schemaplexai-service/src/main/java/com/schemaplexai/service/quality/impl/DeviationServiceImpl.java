package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.WorkspaceMapper;
import com.schemaplexai.model.converter.DeviationConverter;
import com.schemaplexai.model.dto.quality.DeviationDetectRequest;
import com.schemaplexai.model.dto.quality.DeviationQueryRequest;
import com.schemaplexai.model.dto.quality.DeviationStatusUpdateRequest;
import com.schemaplexai.model.entity.QualityDeviation;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.entity.Workspace;
import com.schemaplexai.model.vo.quality.DeviationStatisticsVO;
import com.schemaplexai.model.vo.quality.DeviationVO;
import com.schemaplexai.model.vo.quality.DetectTaskVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.DeviationService;
import com.schemaplexai.service.quality.validator.DeviationValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 偏离检测服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviationServiceImpl implements DeviationService {

    private final QualityDeviationMapper qualityDeviationMapper;
    private final DeviationConverter deviationConverter;
    private final DeviationValidator deviationValidator;
    private final EntityValidator entityValidator;
    private final RabbitTemplate rabbitTemplate;
    private final SpecMapper specMapper;
    private final WorkspaceMapper workspaceMapper;
    private final UserMapper userMapper;

    @Override
    public DetectTaskVO detect(DeviationDetectRequest request) {
        String taskId = UUID.randomUUID().toString();
        log.info("提交偏离检测任务: taskId={}, specId={}, agentExecutionId={}",
                taskId, request.getSpecId(), request.getAgentExecutionId());

        Map<String, Object> message = new HashMap<>();
        message.put("taskId", taskId);
        message.put("specId", request.getSpecId());
        message.put("agentExecutionId", request.getAgentExecutionId());
        message.put("type", "deviation_detect");

        rabbitTemplate.convertAndSend("sf.quality.check", message);
        log.info("偏离检测任务已发送到MQ: taskId={}", taskId);

        return new DetectTaskVO(taskId, "submitted", "检测任务已提交");
    }

    @Override
    public PageResult<DeviationVO> page(DeviationQueryRequest request) {
        var page = new Page<QualityDeviation>(request.getPage(), request.getSize());
        var wrapper = buildWrapper(request);
        var result = qualityDeviationMapper.selectPage(page, wrapper);
        List<DeviationVO> voList = enrich(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public DeviationVO getById(String id) {
        var entity = entityValidator.requireExists(qualityDeviationMapper, id, ResultCode.DEVIATION_NOT_FOUND);
        return enrich(List.of(entity)).stream().findFirst().orElseGet(() -> deviationConverter.toVO(entity));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String id, DeviationStatusUpdateRequest request) {
        var entity = entityValidator.requireExists(qualityDeviationMapper, id, ResultCode.DEVIATION_NOT_FOUND);

        // 校验状态流转合法性
        deviationValidator.validateStatusTransition(entity.getStatus(), request.getStatus());
        // 校验解决时的备注必填
        deviationValidator.validateResolveRemark(request.getStatus(), request.getRemark());

        // 构造更新实体
        var updateEntity = new QualityDeviation();
        updateEntity.setId(id);
        updateEntity.setStatus(request.getStatus());
        updateEntity.setRemark(request.getRemark());
        updateEntity.setUpdatedBy(SecurityUtil.getCurrentUserId());
        updateEntity.setUpdatedAt(LocalDateTime.now());

        // 若为已解决状态，记录解决人与解决时间
        if (DeviationStatusEnum.RESOLVED.getCode().equals(request.getStatus())) {
            updateEntity.setResolvedBy(SecurityUtil.getCurrentUserId());
            updateEntity.setResolvedAt(LocalDateTime.now());
        }

        qualityDeviationMapper.updateById(updateEntity);
        log.info("更新偏离状态: deviationId={}, newStatus={}", id, request.getStatus());
    }

    @Override
    public DeviationStatisticsVO statistics(String specId, String startDate, String endDate) {
        var statisticsVO = new DeviationStatisticsVO();
        var wrapper = new LambdaQueryWrapper<QualityDeviation>()
                .eq(StringUtils.hasText(specId), QualityDeviation::getSpecId, specId);
        List<QualityDeviation> records = qualityDeviationMapper.selectList(wrapper);
        statisticsVO.setTotalCount(records.size());
        statisticsVO.setOpenCount((int) records.stream().filter(item -> DeviationStatusEnum.OPEN.getCode().equals(item.getStatus())).count());
        statisticsVO.setResolvedCount((int) records.stream().filter(item -> DeviationStatusEnum.RESOLVED.getCode().equals(item.getStatus())).count());
        statisticsVO.setAcknowledgedCount((int) records.stream().filter(item -> DeviationStatusEnum.ACKNOWLEDGED.getCode().equals(item.getStatus())).count());
        statisticsVO.setIgnoredCount((int) records.stream().filter(item -> DeviationStatusEnum.IGNORED.getCode().equals(item.getStatus())).count());
        statisticsVO.setCriticalCount((int) records.stream().filter(item -> DeviationSeverityEnum.CRITICAL.getCode().equals(item.getSeverity())).count());
        statisticsVO.setWarningCount((int) records.stream().filter(item -> DeviationSeverityEnum.WARNING.getCode().equals(item.getSeverity())).count());
        statisticsVO.setInfoCount((int) records.stream().filter(item -> DeviationSeverityEnum.INFO.getCode().equals(item.getSeverity())).count());
        statisticsVO.setTypeDistribution(buildDistribution(records, QualityDeviation::getDeviationType));
        statisticsVO.setStatusDistribution(buildDistribution(records, QualityDeviation::getStatus));
        statisticsVO.setProjectDistribution(buildProjectDistribution(records));
        return statisticsVO;
    }

    private LambdaQueryWrapper<QualityDeviation> buildWrapper(DeviationQueryRequest request) {
        var wrapper = new LambdaQueryWrapper<QualityDeviation>();
        if (StringUtils.hasText(request.getSpecId())) {
            wrapper.eq(QualityDeviation::getSpecId, request.getSpecId());
        }
        if (StringUtils.hasText(request.getWorkspaceId())) {
            List<String> specIds = specMapper.selectList(new LambdaQueryWrapper<Spec>()
                            .eq(Spec::getProjectId, request.getWorkspaceId())
                            .or()
                            .apply("workspace_ids::text like {0}", "%" + request.getWorkspaceId() + "%"))
                    .stream()
                    .map(Spec::getId)
                    .filter(StringUtils::hasText)
                    .toList();
            if (specIds.isEmpty()) {
                wrapper.eq(QualityDeviation::getId, "__none__");
            } else {
                wrapper.in(QualityDeviation::getSpecId, specIds);
            }
        }
        if (StringUtils.hasText(request.getDeviationType())) {
            wrapper.eq(QualityDeviation::getDeviationType, request.getDeviationType());
        }
        if (StringUtils.hasText(request.getSeverity())) {
            wrapper.eq(QualityDeviation::getSeverity, request.getSeverity());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(QualityDeviation::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(QualityDeviation::getTitle, request.getKeyword())
                    .or().like(QualityDeviation::getDescription, request.getKeyword()));
        }
        wrapper.orderByDesc(QualityDeviation::getCreatedAt);
        return wrapper;
    }

    private List<DeviationVO> enrich(List<QualityDeviation> entities) {
        List<DeviationVO> result = deviationConverter.toVOList(entities);
        Map<String, Spec> specMap = loadSpecMap(entities.stream().map(QualityDeviation::getSpecId).toList());
        Map<String, Workspace> workspaceMap = loadWorkspaceMap(specMap.values().stream()
                .map(Spec::getProjectId)
                .filter(StringUtils::hasText)
                .toList());
        Map<String, User> userMap = loadUserMap(entities.stream().map(QualityDeviation::getResolvedBy).toList());
        for (DeviationVO vo : result) {
            Spec spec = specMap.get(vo.getSpecId());
            if (spec != null) {
                vo.setSpecName(spec.getName());
                vo.setWorkspaceId(spec.getProjectId());
                Workspace workspace = workspaceMap.get(spec.getProjectId());
                if (workspace != null) {
                    vo.setProjectName(workspace.getName());
                }
            }
            if (StringUtils.hasText(vo.getResolvedByName())) {
                continue;
            }
            QualityDeviation entity = entities.stream().filter(item -> Objects.equals(item.getId(), vo.getId())).findFirst().orElse(null);
            if (entity != null) {
                vo.setSourceType(entity.getSourceType());
                vo.setTaskId(entity.getTaskId());
                if (StringUtils.hasText(entity.getResolvedBy())) {
                    User user = userMap.get(entity.getResolvedBy());
                    vo.setResolvedByName(user != null && StringUtils.hasText(user.getRealName()) ? user.getRealName() : entity.getResolvedBy());
                }
            }
        }
        return result;
    }

    private List<Map<String, Object>> buildDistribution(List<QualityDeviation> records,
                                                        java.util.function.Function<QualityDeviation, String> keyExtractor) {
        return records.stream()
                .collect(Collectors.groupingBy(keyExtractor, LinkedHashMap::new, Collectors.counting()))
                .entrySet()
                .stream()
                .map(entry -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("key", entry.getKey());
                    item.put("count", entry.getValue());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildProjectDistribution(List<QualityDeviation> records) {
        Map<String, Spec> specMap = loadSpecMap(records.stream().map(QualityDeviation::getSpecId).toList());
        Map<String, Workspace> workspaceMap = loadWorkspaceMap(specMap.values().stream()
                .map(Spec::getProjectId)
                .filter(StringUtils::hasText)
                .toList());
        Map<String, Long> grouped = new LinkedHashMap<>();
        for (QualityDeviation record : records) {
            Spec spec = specMap.get(record.getSpecId());
            String projectName = "未关联项目";
            if (spec != null && StringUtils.hasText(spec.getProjectId())) {
                Workspace workspace = workspaceMap.get(spec.getProjectId());
                if (workspace != null) {
                    projectName = workspace.getName();
                }
            }
            grouped.merge(projectName, 1L, Long::sum);
        }
        List<Map<String, Object>> distribution = new ArrayList<>();
        grouped.forEach((key, value) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", key);
            item.put("count", value);
            distribution.add(item);
        });
        return distribution;
    }

    private Map<String, Spec> loadSpecMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return specMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Spec::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, Workspace> loadWorkspaceMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return workspaceMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Workspace::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, User> loadUserMap(Collection<String> ids) {
        List<String> userIds = ids == null ? List.of() : ids.stream().filter(StringUtils::hasText).toList();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(new ArrayList<>(userIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(User::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }
}
