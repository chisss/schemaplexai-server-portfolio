package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.enums.IntentDefectTypeEnum;
import com.schemaplexai.common.enums.IntentDefectStatusEnum;
import com.schemaplexai.common.enums.SpecDocTypeEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.IntentDefectMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.model.converter.IntentDefectConverter;
import com.schemaplexai.model.dto.quality.IntentDefectAnalyzeRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.dto.quality.IntentDefectQueryRequest;
import com.schemaplexai.model.entity.IntentDefect;
import com.schemaplexai.model.vo.quality.AnalyzeTaskVO;
import com.schemaplexai.model.vo.quality.IntentDefectVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.IntentDefectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * 意图缺陷分析服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentDefectServiceImpl implements IntentDefectService {

    private final IntentDefectMapper intentDefectMapper;
    private final IntentDefectConverter intentDefectConverter;
    private final EntityValidator entityValidator;
    private final RabbitTemplate rabbitTemplate;
    private final SpecMapper specMapper;

    /** 合法的意图缺陷状态值 */
    private static final Set<String> VALID_STATUSES = Arrays.stream(IntentDefectStatusEnum.values())
            .map(IntentDefectStatusEnum::getCode)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    @Override
    public AnalyzeTaskVO analyze(IntentDefectAnalyzeRequest request) {
        String taskId = UUID.randomUUID().toString();
        log.info("提交意图缺陷分析任务: taskId={}, specId={}, docType={}",
                taskId, request.getSpecId(), request.getDocType());

        Map<String, Object> message = new HashMap<>();
        message.put("taskId", taskId);
        message.put("specId", request.getSpecId());
        message.put("docType", request.getDocType());
        message.put("type", "intent_defect_analyze");

        rabbitTemplate.convertAndSend("sf.quality.check", message);
        log.info("意图缺陷分析任务已发送到MQ: taskId={}", taskId);

        return new AnalyzeTaskVO(taskId, "submitted", "分析任务已提交");
    }

    @Override
    public PageResult<IntentDefectVO> page(IntentDefectQueryRequest request) {
        var page = new Page<IntentDefect>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<IntentDefect>();

        // 按specId过滤
        if (StringUtils.hasText(request.getSpecId())) {
            wrapper.eq(IntentDefect::getSpecId, request.getSpecId());
        }
        // 按文档类型过滤
        if (StringUtils.hasText(request.getDocType())) {
            wrapper.eq(IntentDefect::getDocType, request.getDocType());
        }
        // 按缺陷类型过滤
        if (StringUtils.hasText(request.getDefectType())) {
            wrapper.eq(IntentDefect::getDefectType, request.getDefectType());
        }
        // 按严重程度过滤
        if (StringUtils.hasText(request.getSeverity())) {
            wrapper.eq(IntentDefect::getSeverity, request.getSeverity());
        }
        // 按状态过滤
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(IntentDefect::getStatus, request.getStatus());
        }
        // 关键词搜索：标题或描述
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w
                    .like(IntentDefect::getTitle, request.getKeyword())
                    .or()
                    .like(IntentDefect::getDescription, request.getKeyword())
            );
        }
        wrapper.orderByDesc(IntentDefect::getCreatedAt);

        var result = intentDefectMapper.selectPage(page, wrapper);
        var voList = enrich(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public IntentDefectVO getById(String id) {
        var entity = entityValidator.requireExists(intentDefectMapper, id, ResultCode.INTENT_DEFECT_NOT_FOUND);
        return enrich(List.of(entity)).stream()
                .findFirst()
                .orElseGet(() -> intentDefectConverter.toVO(entity));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String id, String status) {
        entityValidator.requireExists(intentDefectMapper, id, ResultCode.INTENT_DEFECT_NOT_FOUND);

        // 校验状态合法性
        if (!VALID_STATUSES.contains(status)) {
            throw new BusinessException(ResultCode.BAD_REQUEST);
        }

        var updateEntity = new IntentDefect();
        updateEntity.setId(id);
        updateEntity.setStatus(status);
        updateEntity.setUpdatedBy(SecurityUtil.getCurrentUserId());

        intentDefectMapper.updateById(updateEntity);
        log.info("更新意图缺陷状态: defectId={}, newStatus={}", id, status);
    }

    private List<IntentDefectVO> enrich(List<IntentDefect> entities) {
        List<IntentDefectVO> result = intentDefectConverter.toVOList(entities);
        Map<String, Spec> specMap = loadSpecMap(entities.stream()
                .map(IntentDefect::getSpecId)
                .filter(StringUtils::hasText)
                .toList());
        Map<String, String> docTypeLabelMap = Arrays.stream(SpecDocTypeEnum.values())
                .collect(Collectors.toMap(SpecDocTypeEnum::getCode, SpecDocTypeEnum::getDescription));
        Map<String, String> defectTypeLabelMap = Arrays.stream(IntentDefectTypeEnum.values())
                .collect(Collectors.toMap(IntentDefectTypeEnum::getCode, IntentDefectTypeEnum::getDescription));
        for (IntentDefectVO vo : result) {
            if (!StringUtils.hasText(vo.getSpecName())) {
                Spec spec = specMap.get(vo.getSpecId());
                if (spec != null) {
                    vo.setSpecName(spec.getName());
                }
            }
            vo.setDocTypeLabel(docTypeLabelMap.getOrDefault(vo.getDocType(), vo.getDocType()));
            vo.setDefectTypeLabel(defectTypeLabelMap.getOrDefault(vo.getDefectType(), vo.getDefectType()));
        }
        return result;
    }

    private Map<String, Spec> loadSpecMap(List<String> specIds) {
        if (specIds == null || specIds.isEmpty()) {
            return Map.of();
        }
        return specMapper.selectBatchIds(new ArrayList<>(specIds)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Spec::getId, spec -> spec, (left, right) -> left, LinkedHashMap::new));
    }
}
