package com.schemaplexai.service.quality.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.IntentDefectMapper;
import com.schemaplexai.model.converter.IntentDefectConverter;
import com.schemaplexai.model.dto.quality.IntentDefectAnalyzeRequest;
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

import java.util.Set;
import java.util.UUID;

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

    /** 合法的意图缺陷状态值 */
    private static final Set<String> VALID_STATUSES = Set.of("open", "resolved", "accepted");

    @Override
    public AnalyzeTaskVO analyze(IntentDefectAnalyzeRequest request) {
        String taskId = UUID.randomUUID().toString();
        log.info("提交意图缺陷分析任务: taskId={}, specId={}, docType={}",
                taskId, request.getSpecId(), request.getDocType());

        // TODO: 异步调用AI模型进行意图缺陷分析，将分析结果写入数据库

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
        var voList = intentDefectConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public IntentDefectVO getById(String id) {
        var entity = entityValidator.requireExists(intentDefectMapper, id, ResultCode.INTENT_DEFECT_NOT_FOUND);
        return intentDefectConverter.toVO(entity);
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
}
