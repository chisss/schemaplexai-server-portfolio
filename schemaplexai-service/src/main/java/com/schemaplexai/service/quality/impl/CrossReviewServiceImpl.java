package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.enums.TaskStatusEnum;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.CrossReviewMapper;
import com.schemaplexai.model.converter.CrossReviewConverter;
import com.schemaplexai.model.dto.quality.CrossReviewCreateRequest;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.vo.quality.CrossReviewVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.CrossReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 多模型交叉审查服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrossReviewServiceImpl implements CrossReviewService {

    private final CrossReviewMapper crossReviewMapper;
    private final CrossReviewConverter crossReviewConverter;
    private final EntityValidator entityValidator;
    private final RabbitTemplate rabbitTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CrossReviewVO create(CrossReviewCreateRequest request) {
        var entity = new CrossReview();
        entity.setSpecId(request.getSpecId());
        entity.setTaskId(request.getTaskId());
        entity.setModelAId(request.getModelAId());
        entity.setModelBId(request.getModelBId());
        entity.setStatus(TaskStatusEnum.PENDING.getCode());
        entity.setCreatedBy(SecurityUtil.getCurrentUserId());
        entity.setTenantId(SecurityUtil.getCurrentTenantId());

        crossReviewMapper.insert(entity);
        log.info("创建交叉审查: reviewId={}, specId={}, modelA={}, modelB={}",
                entity.getId(), request.getSpecId(), request.getModelAId(), request.getModelBId());

        Map<String, Object> message = Map.of(
                "reviewId", entity.getId(),
                "specId", request.getSpecId(),
                "taskId", request.getTaskId(),
                "modelAId", request.getModelAId(),
                "modelBId", request.getModelBId(),
                "type", "cross_review"
        );

        rabbitTemplate.convertAndSend("sf.quality.check", message);
        log.info("交叉审查任务已发送到MQ: reviewId={}", entity.getId());

        return crossReviewConverter.toVO(entity);
    }

    @Override
    public PageResult<CrossReviewVO> page(String specId, String status, Integer page, Integer size) {
        var pageParam = new Page<CrossReview>(page, size);
        var wrapper = new LambdaQueryWrapper<CrossReview>();

        // 按specId过滤
        if (StringUtils.hasText(specId)) {
            wrapper.eq(CrossReview::getSpecId, specId);
        }
        // 按状态过滤
        if (StringUtils.hasText(status)) {
            wrapper.eq(CrossReview::getStatus, status);
        }
        wrapper.orderByDesc(CrossReview::getCreatedAt);

        var result = crossReviewMapper.selectPage(pageParam, wrapper);
        var voList = crossReviewConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public CrossReviewVO getById(String id) {
        var entity = entityValidator.requireExists(crossReviewMapper, id, ResultCode.CROSS_REVIEW_NOT_FOUND);
        return crossReviewConverter.toVO(entity);
    }
}
