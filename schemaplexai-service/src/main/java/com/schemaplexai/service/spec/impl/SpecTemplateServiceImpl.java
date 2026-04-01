package com.schemaplexai.service.spec.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.constant.CommonConstant;
import com.schemaplexai.common.enums.SpecStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.SpecTemplateMapper;
import com.schemaplexai.model.converter.SpecConverter;
import com.schemaplexai.model.converter.SpecTemplateConverter;
import com.schemaplexai.model.dto.spec.SpecFromTemplateRequest;
import com.schemaplexai.model.dto.spec.SpecTemplateCreateRequest;
import com.schemaplexai.model.dto.spec.SpecTemplateQueryRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.SpecTemplate;
import com.schemaplexai.model.vo.spec.SpecTemplateVO;
import com.schemaplexai.model.vo.spec.SpecVO;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.spec.SpecTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spec模板管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpecTemplateServiceImpl implements SpecTemplateService {

    private static final String DEFAULT_PRIORITY = "medium";

    private final SpecTemplateMapper specTemplateMapper;
    private final SpecMapper specMapper;
    private final SpecDocumentMapper specDocumentMapper;
    private final SpecTemplateConverter specTemplateConverter;
    private final SpecConverter specConverter;
    private final EntityValidator entityValidator;

    @Override
    public PageResult<SpecTemplateVO> listTemplates(SpecTemplateQueryRequest request) {
        var page = new Page<SpecTemplate>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<SpecTemplate>();

        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w
                    .like(SpecTemplate::getName, request.getKeyword())
                    .or()
                    .like(SpecTemplate::getDescription, request.getKeyword())
            );
        }
        if (StringUtils.hasText(request.getCategory())) {
            wrapper.eq(SpecTemplate::getCategory, request.getCategory());
        }
        if (StringUtils.hasText(request.getDocType())) {
            wrapper.eq(SpecTemplate::getDocType, request.getDocType());
        }
        wrapper.orderByDesc(SpecTemplate::getUsageCount);

        var result = specTemplateMapper.selectPage(page, wrapper);
        return new PageResult<>(
                specTemplateConverter.toVOList(result.getRecords()),
                result.getTotal(), result.getCurrent(), result.getSize()
        );
    }

    @Override
    public SpecTemplateVO getTemplateById(String id) {
        var template = specTemplateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return specTemplateConverter.toVO(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpecTemplateVO createTemplate(SpecTemplateCreateRequest request) {
        var template = specTemplateConverter.fromCreateRequest(request);
        specTemplateMapper.insert(template);

        log.info("创建Spec模板: templateId={}, name={}", template.getId(), template.getName());
        return specTemplateConverter.toVO(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(String id) {
        var template = specTemplateMapper.selectById(id);
        if (template == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        if (Boolean.TRUE.equals(template.getIsBuiltin())) {
            throw new BusinessException(ResultCode.FAIL, "内置模板不可删除");
        }
        specTemplateMapper.deleteById(id);
        log.info("删除Spec模板: templateId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpecVO createSpecFromTemplate(String templateId, SpecFromTemplateRequest request) {
        var template = specTemplateMapper.selectById(templateId);
        if (template == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }

        // 创建 Spec
        var spec = new Spec();
        spec.setName(request.getName());
        spec.setCategory(request.getCategory() != null ? request.getCategory() : template.getCategory());
        spec.setDescription(request.getDescription());
        spec.setPriority(StringUtils.hasText(request.getPriority()) ? request.getPriority() : DEFAULT_PRIORITY);
        spec.setTags(request.getTags());
        spec.setWorkspaceIds(request.getWorkspaceIds());
        spec.setProjectId(resolvePrimaryWorkspaceId(request.getProjectId(), request.getWorkspaceIds()));
        spec.setVersion(CommonConstant.SPEC_INITIAL_VERSION);
        spec.setStatus(SpecStatusEnum.DRAFT.getCode());
        spec.setOwner(SecurityUtil.getCurrentUserId());
        spec.setWorkflowId(request.getWorkflowId());
        spec.setJiraTicket(request.getJiraTicket());
        spec.setTargetBranch(resolveTargetBranch(request.getJiraTicket(), request.getTargetBranch()));
        specMapper.insert(spec);

        // 用模板内容创建对应类型的文档
        var document = new SpecDocument();
        document.setSpecId(spec.getId());
        document.setDocType(template.getDocType());
        document.setContent(template.getContent());
        document.setVersion(1);
        document.setCreatedBy(SecurityUtil.getCurrentUserId());
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedBy(SecurityUtil.getCurrentUserId());
        document.setUpdatedAt(LocalDateTime.now());
        specDocumentMapper.insert(document);

        // 模板使用次数 +1
        var update = new SpecTemplate();
        update.setId(templateId);
        update.setUsageCount(template.getUsageCount() != null ? template.getUsageCount() + 1 : 1);
        specTemplateMapper.updateById(update);

        log.info("从模板创建Spec: specId={}, templateId={}", spec.getId(), templateId);
        return specConverter.toVO(spec);
    }

    private String resolvePrimaryWorkspaceId(String projectId, List<String> workspaceIds) {
        if (workspaceIds != null && !workspaceIds.isEmpty() && StringUtils.hasText(workspaceIds.getFirst())) {
            return workspaceIds.getFirst();
        }
        return projectId;
    }

    private String resolveTargetBranch(String jiraTicket, String targetBranch) {
        if (StringUtils.hasText(targetBranch)) {
            return targetBranch.trim();
        }
        if (StringUtils.hasText(jiraTicket)) {
            return "feature/" + jiraTicket.trim().toUpperCase();
        }
        return null;
    }
}
