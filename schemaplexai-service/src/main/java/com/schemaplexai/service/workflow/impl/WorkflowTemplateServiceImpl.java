package com.schemaplexai.service.workflow.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.converter.WorkflowTemplateConverter;
import com.schemaplexai.model.dto.workflow.WorkflowAiArrangeRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateCreateRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateQueryRequest;
import com.schemaplexai.model.dto.workflow.WorkflowTemplateUpdateRequest;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.workflow.WorkflowAiArrangeVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateStatsVO;
import com.schemaplexai.model.vo.workflow.WorkflowTemplateVO;
import com.schemaplexai.service.workflow.WorkflowTemplateService;
import com.schemaplexai.service.workflow.validator.WorkflowValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工作流模板服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTemplateServiceImpl implements WorkflowTemplateService {

    private final WorkflowTemplateMapper templateMapper;
    private final WorkflowInstanceMapper instanceMapper;
    private final WorkflowTemplateConverter templateConverter;
    private final WorkflowValidator workflowValidator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowTemplateVO create(WorkflowTemplateCreateRequest request) {
        workflowValidator.validateDefinitionFormat(request.getDefinition());

        var template = templateConverter.fromCreateRequest(request);
        templateMapper.insert(template);

        log.info("创建工作流模板成功: templateId={}, name={}", template.getId(), template.getName());
        return templateConverter.toVO(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowTemplateVO update(String id, WorkflowTemplateUpdateRequest request) {
        var template = requireExists(id);
        workflowValidator.validateNotBuiltin(template);

        if (request.getDefinition() != null) {
            workflowValidator.validateDefinitionFormat(request.getDefinition());
        }

        var updateEntity = new WorkflowTemplate();
        updateEntity.setId(id);
        if (StringUtils.hasText(request.getName())) {
            updateEntity.setName(request.getName());
        }
        if (request.getDescription() != null) {
            updateEntity.setDescription(request.getDescription());
        }
        if (StringUtils.hasText(request.getCategory())) {
            updateEntity.setCategory(request.getCategory());
        }
        if (request.getDefinition() != null) {
            updateEntity.setDefinition(request.getDefinition());
        }
        if (request.getRecommendedAgentSkills() != null) {
            updateEntity.setRecommendedAgentSkills(request.getRecommendedAgentSkills());
        }
        updateEntity.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(updateEntity);

        log.info("更新工作流模板成功: templateId={}", id);
        return templateConverter.toVO(templateMapper.selectById(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        var template = requireExists(id);
        workflowValidator.validateNotBuiltin(template);

        // 校验无实例引用
        var instanceCount = instanceMapper.selectCount(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getTemplateId, id)
        );
        if (instanceCount > 0) {
            throw new BusinessException(ResultCode.WORKFLOW_TEMPLATE_IN_USE);
        }

        templateMapper.deleteById(id);
        log.info("删除工作流模板成功: templateId={}", id);
    }

    @Override
    public WorkflowTemplateVO getById(String id) {
        var template = requireExists(id);
        return templateConverter.toVO(template);
    }

    @Override
    public PageResult<WorkflowTemplateVO> page(WorkflowTemplateQueryRequest query) {
        var page = new Page<WorkflowTemplate>(query.getPage(), query.getSize());
        var wrapper = new LambdaQueryWrapper<WorkflowTemplate>();

        if (StringUtils.hasText(query.getKeyword())) {
            wrapper.and(w -> w
                    .like(WorkflowTemplate::getName, query.getKeyword())
                    .or()
                    .like(WorkflowTemplate::getDescription, query.getKeyword())
            );
        }
        if (StringUtils.hasText(query.getCategory())) {
            wrapper.eq(WorkflowTemplate::getCategory, query.getCategory());
        }
        if (query.getIsBuiltin() != null) {
            wrapper.eq(WorkflowTemplate::getIsBuiltin, query.getIsBuiltin());
        }
        wrapper.orderByDesc(WorkflowTemplate::getCreatedAt);

        var result = templateMapper.selectPage(page, wrapper);
        var voList = templateConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public List<WorkflowTemplateVO> listAll() {
        var wrapper = new LambdaQueryWrapper<WorkflowTemplate>()
                .orderByDesc(WorkflowTemplate::getCreatedAt);
        return templateConverter.toVOList(templateMapper.selectList(wrapper));
    }

    private WorkflowTemplate requireExists(String id) {
        var template = templateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ResultCode.WORKFLOW_NOT_FOUND);
        }
        return template;
    }

    @Override
    public WorkflowAiArrangeVO aiArrange(String templateId, WorkflowAiArrangeRequest request) {
        requireExists(templateId);
        // AI 编排占位实现：后续接入内置 Agent 执行引擎
        log.info("AI编排工作流: templateId={}, prompt={}", templateId, request.getPrompt());
        var result = new WorkflowAiArrangeVO();
        result.setStatus(WorkflowInstanceStatusEnum.PENDING.getCode());
        result.setExplanation("AI编排功能正在准备中，请稍后查看执行结果。");
        return result;
    }

    @Override
    public WorkflowTemplateStatsVO getStats() {
        // 统计 published 状态的模板数量
        var activeCount = templateMapper.selectCount(
                new LambdaQueryWrapper<WorkflowTemplate>()
                        .eq(WorkflowTemplate::getStatus, "published")
        );

        // 统计24小时内的实例运行数和成功率
        var since = LocalDateTime.now().minusHours(24);
        var recentInstances = instanceMapper.selectList(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .ge(WorkflowInstance::getCreatedAt, since)
        );
        long totalRuns = recentInstances.size();
        long completedRuns = recentInstances.stream()
                .filter(i -> WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(i.getStatus()))
                .count();
        double avgSuccessRate = totalRuns > 0 ? (completedRuns * 100.0 / totalRuns) : 0.0;

        var stats = new WorkflowTemplateStatsVO();
        stats.setActiveWorkflows(activeCount);
        stats.setAvgSuccessRate(Math.round(avgSuccessRate * 10) / 10.0);
        stats.setTotalRuns24h(totalRuns);
        return stats;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkflowTemplateVO toggleStatus(String id) {
        var template = requireExists(id);
        String currentStatus = template.getStatus();
        // published → archived, 其他 → published
        String newStatus = "published".equals(currentStatus) ? "archived" : "published";

        var updateEntity = new WorkflowTemplate();
        updateEntity.setId(id);
        updateEntity.setStatus(newStatus);
        updateEntity.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(updateEntity);

        log.info("切换工作流模板状态: templateId={}, {} -> {}", id, currentStatus, newStatus);
        return templateConverter.toVO(templateMapper.selectById(id));
    }
}
