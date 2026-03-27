package com.schemaplexai.service.cicd.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.CicdPipelineStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.CicdPipelineMapper;
import com.schemaplexai.model.converter.CicdPipelineConverter;
import com.schemaplexai.model.dto.cicd.CicdPipelineCreateRequest;
import com.schemaplexai.model.dto.cicd.CicdPipelineQueryRequest;
import com.schemaplexai.model.dto.cicd.CicdPipelineUpdateRequest;
import com.schemaplexai.model.entity.CicdPipeline;
import com.schemaplexai.model.vo.cicd.CicdPipelineVO;
import com.schemaplexai.service.cicd.CicdPipelineService;
import com.schemaplexai.service.cicd.validator.CicdPipelineValidator;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.integration.cicd.CicdTrigger;
import com.schemaplexai.service.integration.cicd.CicdTriggerFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * CICD Pipeline服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CicdPipelineServiceImpl implements CicdPipelineService {

    private final CicdPipelineMapper pipelineMapper;
    private final CicdPipelineConverter pipelineConverter;
    private final CicdPipelineValidator pipelineValidator;
    private final EntityValidator entityValidator;
    private final CicdTriggerFactory cicdTriggerFactory;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CicdPipelineVO create(CicdPipelineCreateRequest request) {
        pipelineValidator.validateNameUnique(request.getName());

        var pipeline = pipelineConverter.fromCreateRequest(request);
        pipelineMapper.insert(pipeline);
        log.info("创建CICD Pipeline成功: pipelineId={}, name={}, type={}",
                pipeline.getId(), pipeline.getName(), pipeline.getPipelineType());

        return pipelineConverter.toVO(pipeline);
    }

    @Override
    public PageResult<CicdPipelineVO> page(CicdPipelineQueryRequest request) {
        var page = new Page<CicdPipeline>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<CicdPipeline>();

        if (StringUtils.hasText(request.getPipelineType())) {
            wrapper.eq(CicdPipeline::getPipelineType, request.getPipelineType());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(CicdPipeline::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getWorkspaceId())) {
            wrapper.eq(CicdPipeline::getWorkspaceId, request.getWorkspaceId());
        }
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w.like(CicdPipeline::getName, request.getKeyword())
                    .or().like(CicdPipeline::getDescription, request.getKeyword()));
        }
        wrapper.orderByDesc(CicdPipeline::getCreatedAt);

        var result = pipelineMapper.selectPage(page, wrapper);
        var voList = pipelineConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public CicdPipelineVO getById(String id) {
        var pipeline = entityValidator.requireExists(pipelineMapper, id, ResultCode.CICD_PIPELINE_NOT_FOUND);
        return pipelineConverter.toVO(pipeline);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CicdPipelineVO update(String id, CicdPipelineUpdateRequest request) {
        var pipeline = entityValidator.requireExists(pipelineMapper, id, ResultCode.CICD_PIPELINE_NOT_FOUND);

        if (StringUtils.hasText(request.getName())) {
            pipeline.setName(request.getName());
        }
        if (request.getConfig() != null) {
            pipeline.setConfig(request.getConfig());
        }
        if (request.getTriggerRules() != null) {
            pipeline.setTriggerRules(request.getTriggerRules());
        }
        if (StringUtils.hasText(request.getStatus())) {
            pipeline.setStatus(request.getStatus());
        }
        if (request.getDescription() != null) {
            pipeline.setDescription(request.getDescription());
        }

        pipelineMapper.updateById(pipeline);
        log.info("更新CICD Pipeline成功: pipelineId={}", id);

        return pipelineConverter.toVO(pipeline);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        entityValidator.requireExists(pipelineMapper, id, ResultCode.CICD_PIPELINE_NOT_FOUND);
        pipelineMapper.deleteById(id);
        log.info("删除CICD Pipeline成功: pipelineId={}", id);
    }

    @Override
    public Map<String, Object> trigger(String id) {
        var pipeline = entityValidator.requireExists(pipelineMapper, id, ResultCode.CICD_PIPELINE_NOT_FOUND);

        // 根据 pipelineType 获取对应的触发器并执行
        Map<String, Object> result;
        try {
            CicdTrigger trigger = cicdTriggerFactory.getTrigger(pipeline.getPipelineType());
            result = trigger.trigger(pipeline.getConfig());
            log.info("触发CICD Pipeline构建成功: pipelineId={}, type={}", id, pipeline.getPipelineType());

            // 更新最近运行状态
            pipeline.setLastRunAt(LocalDateTime.now());
            pipeline.setLastRunStatus(CicdPipelineStatusEnum.RUNNING.getCode());
            pipeline.setStatus(CommonConstant.STATUS_ACTIVE);
        } catch (Exception e) {
            log.error("触发CICD Pipeline构建失败: pipelineId={}, type={}", id, pipeline.getPipelineType(), e);
            result = new HashMap<>();
            result.put("error", e.getMessage());

            pipeline.setLastRunAt(LocalDateTime.now());
            pipeline.setLastRunStatus(CicdPipelineStatusEnum.FAILED.getCode());
        }

        pipelineMapper.updateById(pipeline);
        return result;
    }
}
