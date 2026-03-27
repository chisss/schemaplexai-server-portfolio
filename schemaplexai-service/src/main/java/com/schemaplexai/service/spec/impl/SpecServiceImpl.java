package com.schemaplexai.service.spec.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.SpecStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.SpecVersionMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.model.converter.SpecConverter;
import com.schemaplexai.model.converter.SpecDocumentConverter;
import com.schemaplexai.model.dto.spec.SpecCreateRequest;
import com.schemaplexai.model.dto.spec.SpecDiffRequest;
import com.schemaplexai.model.dto.spec.SpecDocumentRequest;
import com.schemaplexai.model.dto.spec.SpecQueryRequest;
import com.schemaplexai.model.dto.spec.SpecUpdateRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.SpecVersion;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.vo.spec.SpecDiffVO;
import com.schemaplexai.model.vo.spec.SpecDocumentVO;
import com.schemaplexai.model.vo.spec.SpecVO;
import com.schemaplexai.model.vo.spec.SpecVersionVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.spec.SpecService;
import com.schemaplexai.service.spec.validator.SpecStatusValidator;
import com.schemaplexai.service.spec.handler.SpecVersionHandler;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.mq.message.WorkflowTriggerMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spec管理服务实现 — 编排器模式
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpecServiceImpl implements SpecService {

    private final SpecMapper specMapper;
    private final SpecDocumentMapper specDocumentMapper;
    private final SpecVersionMapper specVersionMapper;
    private final SpecConverter specConverter;
    private final SpecDocumentConverter specDocumentConverter;
    private final SpecStatusValidator specStatusValidator;
    private final SpecVersionHandler specVersionHandler;
    private final EntityValidator entityValidator;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowInstanceService workflowInstanceService;
    private final RabbitTemplate rabbitTemplate;

    @Override
    public PageResult<SpecVO> listSpecs(SpecQueryRequest request) {
        var page = new Page<Spec>(request.getPage(), request.getSize());
        var wrapper = new LambdaQueryWrapper<Spec>();

        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.and(w -> w
                    .like(Spec::getName, request.getKeyword())
                    .or()
                    .like(Spec::getDescription, request.getKeyword())
            );
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(Spec::getStatus, request.getStatus());
        }
        if (StringUtils.hasText(request.getCategory())) {
            wrapper.eq(Spec::getCategory, request.getCategory());
        }
        if (StringUtils.hasText(request.getOwner())) {
            wrapper.eq(Spec::getOwner, request.getOwner());
        }
        if (StringUtils.hasText(request.getProjectId())) {
            wrapper.eq(Spec::getProjectId, request.getProjectId());
        }
        wrapper.orderByDesc(Spec::getCreatedAt);

        var result = specMapper.selectPage(page, wrapper);
        var voList = specConverter.toVOList(result.getRecords());
        return new PageResult<>(voList, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public SpecVO getSpecById(String id) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        return enrichWithDocuments(spec);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpecVO createSpec(SpecCreateRequest request) {
        var spec = specConverter.fromCreateRequest(request);
        spec.setOwner(SecurityUtil.getCurrentUserId());
        specMapper.insert(spec);

        log.info("创建Spec成功: specId={}, name={}", spec.getId(), spec.getName());
        return specConverter.toVO(spec);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpecVO updateSpec(String id, SpecUpdateRequest request) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        validateEditable(spec);

        var updateEntity = new Spec();
        updateEntity.setId(id);
        updateEntity.setName(request.getName());
        updateEntity.setCategory(request.getCategory());
        updateEntity.setDescription(request.getDescription());
        updateEntity.setTags(request.getTags());
        updateEntity.setWorkflowId(request.getWorkflowId());
        specMapper.updateById(updateEntity);

        log.info("更新Spec成功: specId={}", id);
        return getSpecById(id);
    }

    private static final Set<String> UNDELETABLE_STATUSES = Set.of(
            SpecStatusEnum.IN_PROGRESS.getCode(),
            SpecStatusEnum.ACCEPTANCE.getCode(),
            SpecStatusEnum.COMPLETED.getCode()
    );

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSpec(String id) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        if (UNDELETABLE_STATUSES.contains(spec.getStatus())) {
            throw new BusinessException(ResultCode.SPEC_STATUS_NOT_ALLOWED);
        }
        specDocumentMapper.delete(new LambdaQueryWrapper<SpecDocument>().eq(SpecDocument::getSpecId, id));
        specVersionMapper.delete(new LambdaQueryWrapper<SpecVersion>().eq(SpecVersion::getSpecId, id));
        specMapper.deleteById(id);
        log.info("删除Spec成功: specId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitForReview(String id, String docType) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        specVersionHandler.requireDocumentExists(id, docType);
        var targetStatus = specStatusValidator.resolveReviewStatus(docType);
        specStatusValidator.validateTransition(spec.getStatus(), targetStatus);
        updateSpecStatus(id, targetStatus);
        log.info("Spec提交审批: specId={}, docType={}, newStatus={}", id, docType, targetStatus);

        String requestId = UUID.randomUUID().toString();
        WorkflowTriggerMessage message = WorkflowTriggerMessage.builder()
                .specId(id)
                .docType(docType)
                .triggerType("spec-review")
                .workflowTemplateId(spec.getWorkflowId())
                .triggerBy(SecurityUtil.getCurrentUserId())
                .tenantId(SecurityUtil.getCurrentTenantId())
                .triggeredAt(LocalDateTime.now())
                .requestId(requestId)
                .build();
        rabbitTemplate.convertAndSend("sf.workflow", "workflow.trigger.spec-review", message);
        log.info("已发送工作流触发消息: specId={}, triggerType=spec-review, requestId={}", id, requestId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(String id) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        var targetStatus = specStatusValidator.resolveApprovedStatus(spec.getStatus());
        specStatusValidator.validateTransition(spec.getStatus(), targetStatus);
        updateSpecStatus(id, targetStatus);
        log.info("Spec审批通过: specId={}, newStatus={}", id, targetStatus);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(String id) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        var targetStatus = specStatusValidator.resolveRejectedStatus(spec.getStatus());
        specStatusValidator.validateTransition(spec.getStatus(), targetStatus);
        updateSpecStatus(id, targetStatus);
        log.info("Spec审批驳回: specId={}, newStatus={}", id, targetStatus);
    }

    @Override
    public SpecDocumentVO getDocument(String specId, String docType) {
        entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        var document = specVersionHandler.getDocument(specId, docType);
        return specDocumentConverter.toVO(document);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpecDocumentVO saveDocument(String specId, String docType, SpecDocumentRequest request) {
        var spec = entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        validateEditable(spec);
        var document = specVersionHandler.saveDocument(specId, docType, request);
        return specDocumentConverter.toVO(document);
    }

    @Override
    public List<SpecVersionVO> getVersionHistory(String specId, String docType) {
        entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        var versions = specVersionHandler.getVersionHistory(specId, docType);
        return specDocumentConverter.toVersionVOList(versions);
    }

    @Override
    public SpecVersionVO getVersionById(String specId, String versionId) {
        entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        var version = specVersionMapper.selectById(versionId);
        if (version == null || !specId.equals(version.getSpecId())) {
            throw new BusinessException(ResultCode.SPEC_DOCUMENT_NOT_FOUND);
        }
        return specDocumentConverter.toVersionVO(version);
    }

    /** 版本Diff对比 */
    @Override
    public SpecDiffVO diffVersions(String specId, SpecDiffRequest request) {
        entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);

        var sourceVersion = specVersionMapper.selectById(request.getSourceVersionId());
        var targetVersion = specVersionMapper.selectById(request.getTargetVersionId());

        if (sourceVersion == null || !specId.equals(sourceVersion.getSpecId())) {
            throw new BusinessException(ResultCode.SPEC_DOCUMENT_NOT_FOUND);
        }
        if (targetVersion == null || !specId.equals(targetVersion.getSpecId())) {
            throw new BusinessException(ResultCode.SPEC_DOCUMENT_NOT_FOUND);
        }

        // 行级 Diff 计算
        var sourceLines = splitLines(sourceVersion.getContent());
        var targetLines = splitLines(targetVersion.getContent());
        var diffLines = computeDiff(sourceLines, targetLines);

        int addedCount = 0;
        int removedCount = 0;
        for (var line : diffLines) {
            if ("added".equals(line.getType())) addedCount++;
            if ("removed".equals(line.getType())) removedCount++;
        }

        var result = new SpecDiffVO();
        result.setSourceVersion(sourceVersion.getVersionNumber());
        result.setTargetVersion(targetVersion.getVersionNumber());
        result.setDocType(sourceVersion.getDocType());
        result.setLines(diffLines);
        result.setAddedCount(addedCount);
        result.setRemovedCount(removedCount);
        return result;
    }

    @Override
    public WorkflowInstanceVO getWorkflowTracking(String specId) {
        entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        var instance = workflowInstanceMapper.selectOne(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getSpecId, specId)
                        .orderByDesc(WorkflowInstance::getCreatedAt)
                        .last("LIMIT 1")
        );
        if (instance == null) {
            return null;
        }
        return workflowInstanceService.getById(instance.getId());
    }

    // ===== 内部方法 =====

    private static final Set<String> EDITABLE_STATUSES = Set.of(
            SpecStatusEnum.DRAFT.getCode(),
            SpecStatusEnum.REQUIREMENTS_APPROVED.getCode(),
            SpecStatusEnum.DESIGN_APPROVED.getCode()
    );

    private void validateEditable(Spec spec) {
        if (!EDITABLE_STATUSES.contains(spec.getStatus())) {
            throw new BusinessException(ResultCode.SPEC_STATUS_NOT_ALLOWED);
        }
    }

    private void updateSpecStatus(String id, String status) {
        var updateEntity = new Spec();
        updateEntity.setId(id);
        updateEntity.setStatus(status);
        specMapper.updateById(updateEntity);
    }

    private SpecVO enrichWithDocuments(Spec spec) {
        var vo = specConverter.toVO(spec);
        var documents = specVersionHandler.getAllDocuments(spec.getId());
        vo.setDocuments(specDocumentConverter.toVOList(documents));
        return vo;
    }

    /**
     * 按行分割文本
     */
    private String[] splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return new String[0];
        }
        return text.split("\n", -1);
    }

    /**
     * 简单行级 Diff 算法（基于 LCS）
     */
    private List<SpecDiffVO.DiffLine> computeDiff(String[] source, String[] target) {
        int m = source.length;
        int n = target.length;

        // LCS 矩阵
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (source[i - 1].equals(target[j - 1])) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }

        // 回溯生成差异
        var result = new ArrayList<SpecDiffVO.DiffLine>();
        int i = m, j = n;
        var tempList = new ArrayList<SpecDiffVO.DiffLine>();
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && source[i - 1].equals(target[j - 1])) {
                tempList.add(new SpecDiffVO.DiffLine("unchanged", source[i - 1], i, j));
                i--;
                j--;
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                tempList.add(new SpecDiffVO.DiffLine("added", target[j - 1], null, j));
                j--;
            } else {
                tempList.add(new SpecDiffVO.DiffLine("removed", source[i - 1], i, null));
                i--;
            }
        }

        // 反转（因为回溯是从后往前的）
        for (int k = tempList.size() - 1; k >= 0; k--) {
            result.add(tempList.get(k));
        }
        return result;
    }
}
