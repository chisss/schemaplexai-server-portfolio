package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.AiModelMapper;
import com.schemaplexai.dao.mapper.CrossReviewMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.converter.CrossReviewConverter;
import com.schemaplexai.model.dto.quality.CrossReviewCreateRequest;
import com.schemaplexai.model.entity.AiModel;
import com.schemaplexai.model.entity.CrossReview;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.vo.quality.CrossReviewVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.CrossReviewService;
import com.schemaplexai.service.quality.QualityCrossReviewExecutionService;
import com.schemaplexai.service.quality.QualityProfileResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final QualityCrossReviewExecutionService qualityCrossReviewExecutionService;
    private final QualityProfileResolverService qualityProfileResolverService;
    private final SpecMapper specMapper;
    private final AiModelMapper aiModelMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CrossReviewVO create(CrossReviewCreateRequest request) {
        List<String> modelIds = request.getModelIds();
        if (CollectionUtils.isEmpty(modelIds) && StringUtils.hasText(request.getProfileId())) {
            modelIds = qualityProfileResolverService.listModelIds(request.getProfileId());
        }
        var entity = qualityCrossReviewExecutionService.createAndExecute(
                request.getSpecId(),
                request.getTaskId(),
                request.getProfileId(),
                request.getIssueType(),
                modelIds,
                "manual",
                null,
                null
        );
        if (entity == null) {
            throw new IllegalArgumentException("未找到可用模型，无法发起交叉审查");
        }
        log.info("创建交叉审查并完成执行: reviewId={}, specId={}", entity.getId(), request.getSpecId());
        return enrichVO(entity);
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
        var voList = result.getRecords().stream().map(this::enrichVO).toList();
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public CrossReviewVO getById(String id) {
        var entity = entityValidator.requireExists(crossReviewMapper, id, ResultCode.CROSS_REVIEW_NOT_FOUND);
        return enrichVO(entity);
    }

    private CrossReviewVO enrichVO(CrossReview entity) {
        CrossReviewVO vo = crossReviewConverter.toVO(entity);
        Spec spec = specMapper.selectById(entity.getSpecId());
        if (spec != null) {
            vo.setSpecName(spec.getName());
        }
        Map<String, String> modelNameMap = loadModelNameMap(entity.getModelAId(), entity.getModelBId());
        vo.setModelAName(modelNameMap.get(entity.getModelAId()));
        vo.setModelBName(modelNameMap.get(entity.getModelBId()));
        return vo;
    }

    private Map<String, String> loadModelNameMap(String... ids) {
        List<String> modelIds = new ArrayList<>();
        for (String id : ids) {
            if (StringUtils.hasText(id)) {
                modelIds.add(id);
            }
        }
        if (modelIds.isEmpty()) {
            return Map.of();
        }
        return aiModelMapper.selectBatchIds(modelIds).stream()
                .collect(Collectors.toMap(AiModel::getId, AiModel::getName, (left, right) -> left, LinkedHashMap::new));
    }
}
