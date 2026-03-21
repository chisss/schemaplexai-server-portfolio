package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.DeviationSeverityEnum;
import com.schemaplexai.common.enums.DeviationStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.QualityDeviationMapper;
import com.schemaplexai.model.converter.DeviationConverter;
import com.schemaplexai.model.dto.quality.DeviationDetectRequest;
import com.schemaplexai.model.dto.quality.DeviationQueryRequest;
import com.schemaplexai.model.dto.quality.DeviationStatusUpdateRequest;
import com.schemaplexai.model.entity.QualityDeviation;
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
import java.util.UUID;

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

    @Override
    public DetectTaskVO detect(DeviationDetectRequest request) {
        String taskId = UUID.randomUUID().toString();
        log.info("提交偏离检测任务: taskId={}, specId={}, agentExecutionId={}",
                taskId, request.getSpecId(), request.getAgentExecutionId());

        // TODO: 异步发送MQ消息，触发偏离检测引擎执行实际检测逻辑

        return new DetectTaskVO(taskId, "submitted", "检测任务已提交");
    }

    @Override
    public PageResult<DeviationVO> page(DeviationQueryRequest request) {
        var page = new Page<QualityDeviation>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<QualityDeviation>();

        // 按specId过滤
        if (StringUtils.hasText(request.getSpecId())) {
            wrapper.eq(QualityDeviation::getSpecId, request.getSpecId());
        }
        // 按偏离类型过滤
        if (StringUtils.hasText(request.getDeviationType())) {
            wrapper.eq(QualityDeviation::getDeviationType, request.getDeviationType());
        }
        // 按严重程度过滤
        if (StringUtils.hasText(request.getSeverity())) {
            wrapper.eq(QualityDeviation::getSeverity, request.getSeverity());
        }
        // 按状态过滤
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(QualityDeviation::getStatus, request.getStatus());
        }
        // 关键词搜索：标题或描述
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w
                    .like(QualityDeviation::getTitle, request.getKeyword())
                    .or()
                    .like(QualityDeviation::getDescription, request.getKeyword())
            );
        }
        wrapper.orderByDesc(QualityDeviation::getCreatedAt);

        var result = qualityDeviationMapper.selectPage(page, wrapper);
        var voList = deviationConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public DeviationVO getById(String id) {
        var entity = entityValidator.requireExists(qualityDeviationMapper, id, ResultCode.DEVIATION_NOT_FOUND);
        return deviationConverter.toVO(entity);
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

        // 基础查询条件：按specId过滤
        var baseWrapper = new LambdaQueryWrapper<QualityDeviation>()
                .eq(StringUtils.hasText(specId), QualityDeviation::getSpecId, specId);

        // 总数
        Long totalCount = qualityDeviationMapper.selectCount(baseWrapper);
        statisticsVO.setTotalCount(totalCount.intValue());

        // 按状态统计
        Long openCount = qualityDeviationMapper.selectCount(
                new LambdaQueryWrapper<QualityDeviation>()
                        .eq(StringUtils.hasText(specId), QualityDeviation::getSpecId, specId)
                        .eq(QualityDeviation::getStatus, DeviationStatusEnum.OPEN.getCode())
        );
        statisticsVO.setOpenCount(openCount.intValue());

        Long resolvedCount = qualityDeviationMapper.selectCount(
                new LambdaQueryWrapper<QualityDeviation>()
                        .eq(StringUtils.hasText(specId), QualityDeviation::getSpecId, specId)
                        .eq(QualityDeviation::getStatus, DeviationStatusEnum.RESOLVED.getCode())
        );
        statisticsVO.setResolvedCount(resolvedCount.intValue());

        // 按严重程度统计
        Long criticalCount = qualityDeviationMapper.selectCount(
                new LambdaQueryWrapper<QualityDeviation>()
                        .eq(StringUtils.hasText(specId), QualityDeviation::getSpecId, specId)
                        .eq(QualityDeviation::getSeverity, DeviationSeverityEnum.CRITICAL.getCode())
        );
        statisticsVO.setCriticalCount(criticalCount.intValue());

        Long warningCount = qualityDeviationMapper.selectCount(
                new LambdaQueryWrapper<QualityDeviation>()
                        .eq(StringUtils.hasText(specId), QualityDeviation::getSpecId, specId)
                        .eq(QualityDeviation::getSeverity, DeviationSeverityEnum.WARNING.getCode())
        );
        statisticsVO.setWarningCount(warningCount.intValue());

        Long infoCount = qualityDeviationMapper.selectCount(
                new LambdaQueryWrapper<QualityDeviation>()
                        .eq(StringUtils.hasText(specId), QualityDeviation::getSpecId, specId)
                        .eq(QualityDeviation::getSeverity, DeviationSeverityEnum.INFO.getCode())
        );
        statisticsVO.setInfoCount(infoCount.intValue());

        return statisticsVO;
    }
}
