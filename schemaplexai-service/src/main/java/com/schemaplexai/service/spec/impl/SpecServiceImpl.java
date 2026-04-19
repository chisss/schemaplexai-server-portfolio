package com.schemaplexai.service.spec.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.enums.NodeTypeEnum;
import com.schemaplexai.common.enums.SpecLifecycleModeEnum;
import com.schemaplexai.common.enums.SpecStatusEnum;
import com.schemaplexai.common.enums.WorkflowInstanceStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecMapper;
import com.schemaplexai.dao.mapper.SpecVersionMapper;
import com.schemaplexai.dao.mapper.UserMapper;
import com.schemaplexai.dao.mapper.WorkflowInstanceMapper;
import com.schemaplexai.dao.mapper.WorkflowNodeExecutionMapper;
import com.schemaplexai.dao.mapper.WorkflowTemplateMapper;
import com.schemaplexai.model.converter.SpecConverter;
import com.schemaplexai.model.converter.SpecDocumentConverter;
import com.schemaplexai.model.dto.spec.SpecCreateRequest;
import com.schemaplexai.model.dto.spec.SpecDiffRequest;
import com.schemaplexai.model.dto.spec.SpecDocumentRequest;
import com.schemaplexai.model.dto.spec.SpecDocumentSubmitRequest;
import com.schemaplexai.model.dto.spec.SpecQueryRequest;
import com.schemaplexai.model.dto.spec.SpecUpdateRequest;
import com.schemaplexai.model.dto.spec.SpecWorkflowStartRequest;
import com.schemaplexai.model.dto.workflow.WorkflowInstanceCreateRequest;
import com.schemaplexai.model.entity.Spec;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.SpecVersion;
import com.schemaplexai.model.entity.User;
import com.schemaplexai.model.entity.WorkflowInstance;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import com.schemaplexai.model.entity.WorkflowTemplate;
import com.schemaplexai.model.vo.spec.SpecDiffVO;
import com.schemaplexai.model.vo.spec.SpecDocumentVO;
import com.schemaplexai.model.vo.spec.SpecWorkbenchNodeVO;
import com.schemaplexai.model.vo.spec.SpecWorkbenchVO;
import com.schemaplexai.model.vo.spec.SpecVO;
import com.schemaplexai.model.vo.spec.SpecVersionVO;
import com.schemaplexai.model.vo.workflow.WorkflowInstanceVO;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import com.schemaplexai.service.artifact.ArtifactService;
import com.schemaplexai.service.common.EntityValidator;
import com.schemaplexai.service.quality.runtime.BuiltinQualityAssuranceService;
import com.schemaplexai.service.spec.SpecService;
import com.schemaplexai.service.spec.handler.SpecVersionHandler;
import com.schemaplexai.service.spec.validator.SpecStatusValidator;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.ReviewSessionService;
import com.schemaplexai.service.workflow.engine.WorkflowNodeEngine;
import com.schemaplexai.service.workflow.runtime.SpecWorkflowRuntimeService;
import com.schemaplexai.service.mq.message.WorkflowTriggerMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final UserMapper userMapper;
    private final WorkflowInstanceMapper workflowInstanceMapper;
    private final WorkflowNodeExecutionMapper workflowNodeExecutionMapper;
    private final WorkflowTemplateMapper workflowTemplateMapper;
    private final WorkflowInstanceService workflowInstanceService;
    private final ReviewSessionService reviewSessionService;
    private final WorkflowNodeEngine workflowNodeEngine;
    private final SpecWorkflowRuntimeService specWorkflowRuntimeService;
    private final BuiltinQualityAssuranceService builtinQualityAssuranceService;
    private final RabbitTemplate rabbitTemplate;
    private final ArtifactService artifactService;

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
        if (StringUtils.hasText(request.getSpecType())) {
            wrapper.eq(Spec::getSpecType, request.getSpecType());
        }
        if (StringUtils.hasText(request.getPriority())) {
            wrapper.eq(Spec::getPriority, request.getPriority());
        }
        if (StringUtils.hasText(request.getOwner())) {
            wrapper.eq(Spec::getOwner, request.getOwner());
        }
        if (StringUtils.hasText(request.getProjectId())) {
            wrapper.eq(Spec::getProjectId, request.getProjectId());
        }
        wrapper.orderByDesc(Spec::getCreatedAt);

        var result = specMapper.selectPage(page, wrapper);
        var voList = enrichSpecVOs(specConverter.toVOList(result.getRecords()));
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
        spec.setSpecType(resolveSpecType(request.getSpecType()));
        spec.setProfileData(request.getProfileData());
        spec.setOwner(SecurityUtil.getCurrentUserId());
        spec.setPriority(StringUtils.hasText(request.getPriority()) ? request.getPriority() : "medium");
        spec.setProjectId(resolvePrimaryWorkspaceId(request));
        spec.setTargetBranch(resolveTargetBranch(request.getJiraTicket(), request.getTargetBranch(), spec.getSpecType()));
        if (isMarketingSpec(spec.getSpecType())) {
            spec.setJiraTicket(null);
        }
        specMapper.insert(spec);

        WorkflowInstanceVO workflowInstance = createWorkflowInstance(spec, request);
        WorkflowInstance draftInstance = normalizeDraftWorkflowInstance(workflowInstance.getId());
        if (draftInstance != null) {
            workflowInstance = workflowInstanceService.getById(draftInstance.getId());
        }
        spec.setWorkflowInstanceId(workflowInstance.getId());
        syncSpecWithWorkflow(spec.getId(), workflowInstance);

        log.info("创建Spec成功并准备工作流草稿: specId={}, instanceId={}", spec.getId(), workflowInstance.getId());
        return getSpecById(spec.getId());
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
        updateEntity.setSpecType(resolveSpecType(request.getSpecType(), spec.getSpecType()));
        if (StringUtils.hasText(request.getPriority())) {
            updateEntity.setPriority(request.getPriority());
        }
        updateEntity.setDescription(request.getDescription());
        updateEntity.setProfileData(request.getProfileData());
        updateEntity.setTags(request.getTags());
        updateEntity.setWorkflowId(request.getWorkflowId());
        if (isMarketingSpec(updateEntity.getSpecType())) {
            updateEntity.setJiraTicket(null);
            updateEntity.setTargetBranch(null);
        } else {
            updateEntity.setJiraTicket(request.getJiraTicket());
            updateEntity.setTargetBranch(resolveTargetBranch(request.getJiraTicket(), request.getTargetBranch(), updateEntity.getSpecType()));
        }
        specMapper.updateById(updateEntity);

        log.info("更新Spec成功: specId={}", id);
        return getSpecById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpecVO startWorkflow(String id, SpecWorkflowStartRequest request) {
        Spec spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        requireWorkflowMode(spec);
        WorkflowInstance instance = requireWorkflowInstance(spec);
        boolean awaitingOriginalRequirement = isAwaitingOriginalRequirement(instance);
        if (awaitingOriginalRequirement) {
            instance = normalizeDraftWorkflowInstance(instance.getId());
        } else if (instance.getStartedAt() != null
                || WorkflowInstanceStatusEnum.RUNNING.getCode().equals(instance.getStatus())
                || WorkflowInstanceStatusEnum.PAUSED.getCode().equals(instance.getStatus())
                || WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(instance.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED, "当前 Spec 工作流已启动，不能重复提交原始需求");
        }

        String description = request.getDescription().trim();
        Spec specUpdate = new Spec();
        specUpdate.setId(id);
        specUpdate.setDescription(description);
        specUpdate.setUpdatedAt(LocalDateTime.now());
        specMapper.updateById(specUpdate);
        spec.setDescription(description);

        WorkflowTriggerMessage triggerMessage = WorkflowTriggerMessage.builder()
                .specId(spec.getId())
                .docType("requirements")
                .triggerType("spec-workbench")
                .tenantId(SecurityUtil.getCurrentTenantId())
                .triggerBy(SecurityUtil.getCurrentUserId())
                .triggeredAt(LocalDateTime.now())
                .requestId(UUID.randomUUID().toString())
                .build();

        Map<String, Object> variables = new LinkedHashMap<>(instance.getVariables() != null
                ? instance.getVariables() : Map.of());
        variables.putAll(specWorkflowRuntimeService.buildRuntimeVariables(spec, triggerMessage));
        variables.put("specDescription", description);
        variables.put("originalRequirement", description);

        WorkflowInstance instanceUpdate = new WorkflowInstance();
        instanceUpdate.setId(instance.getId());
        instanceUpdate.setVariables(variables);
        instanceUpdate.setUpdatedAt(LocalDateTime.now());
        workflowInstanceMapper.updateById(instanceUpdate);

        WorkflowInstanceVO workflowInstance = workflowInstanceService.start(instance.getId());
        syncSpecWithWorkflow(id, workflowInstance);
        log.info("Spec 原始需求已提交并启动工作流: specId={}, instanceId={}", id, instance.getId());
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
        requireLegacyMode(spec);
        specVersionHandler.requireDocumentExists(id, docType);
        var qualitySummary = builtinQualityAssuranceService.analyzeIntentDefects(id, docType);
        var targetStatus = specStatusValidator.resolveReviewStatus(docType);
        specStatusValidator.validateTransition(spec.getStatus(), targetStatus);
        updateSpecStatus(id, targetStatus);
        log.info("Spec提交审批: specId={}, docType={}, newStatus={}, qualitySummary={}",
                id, docType, targetStatus, qualitySummary.get("qualitySummary"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approve(String id) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        requireLegacyMode(spec);
        var targetStatus = specStatusValidator.resolveApprovedStatus(spec.getStatus());
        specStatusValidator.validateTransition(spec.getStatus(), targetStatus);
        updateSpecStatus(id, targetStatus);
        log.info("Spec审批通过: specId={}, newStatus={}", id, targetStatus);
        if (SpecStatusEnum.REQUIREMENTS_APPROVED.getCode().equals(targetStatus)) {
            triggerWorkflow(spec);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reject(String id) {
        var spec = entityValidator.requireExists(specMapper, id, ResultCode.SPEC_NOT_FOUND);
        requireLegacyMode(spec);
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
    public SpecWorkbenchVO getWorkbench(String specId) {
        Spec spec = entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        SpecWorkbenchVO workbench = new SpecWorkbenchVO();
        WorkflowInstance instance = resolveWorkflowInstance(spec);
        workbench.setSpec(getSpecById(specId));
        if (instance == null) {
            return workbench;
        }
        instance = normalizeDraftWorkflowInstance(instance.getId());
        WorkflowInstanceVO workflowInstance = workflowInstanceService.getById(instance.getId());
        syncSpecWithWorkflow(specId, workflowInstance);
        workbench.setSpec(getSpecById(specId));
        workbench.setWorkflowInstance(workflowInstance);
        workbench.setNodes(buildWorkbenchNodes(instance, workflowInstance));
        return workbench;
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
    @Transactional(rollbackFor = Exception.class)
    public SpecDocumentVO saveWorkbenchDocument(String specId, String nodeId, SpecDocumentRequest request) {
        Spec spec = entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        requireWorkflowMode(spec);
        WorkflowInstance instance = requireWorkflowInstance(spec);
        Map<String, Object> nodeDef = requireNodeDef(instance, nodeId);
        validateDocumentNode(nodeDef, nodeId);
        String docType = resolveDocumentType(nodeDef, nodeId);
        SpecDocument document = specVersionHandler.saveDocument(specId, docType, nodeId, request);
        appendDocumentVariables(instance, docType, request.getContent());
        return specDocumentConverter.toVO(document);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SpecDocumentVO submitWorkbenchDocument(String specId, String nodeId, SpecDocumentSubmitRequest request) {
        Spec spec = entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        requireWorkflowMode(spec);
        WorkflowInstance instance = requireWorkflowInstance(spec);
        validateCurrentWorkbenchNode(instance, nodeId, "当前文档节点不可提交");
        Map<String, Object> nodeDef = requireNodeDef(instance, nodeId);
        validateDocumentNode(nodeDef, nodeId);
        WorkflowNodeExecution execution = requireNodeExecution(instance.getId(), nodeId);
        if (!WorkflowInstanceStatusEnum.PENDING.getCode().equals(execution.getStatus())
                && !WorkflowInstanceStatusEnum.RUNNING.getCode().equals(execution.getStatus())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED, "当前文档节点不可提交");
        }

        SpecDocumentRequest saveRequest = new SpecDocumentRequest();
        saveRequest.setContent(request.getContent());
        saveRequest.setChangeSummary(request.getChangeSummary());
        String docType = resolveDocumentType(nodeDef, nodeId);
        SpecDocument document = specVersionHandler.saveDocument(specId, docType, nodeId, saveRequest);
        appendDocumentVariables(instance, docType, request.getContent());

        Map<String, Object> outputData = new LinkedHashMap<>();
        outputData.put("documentId", document.getId());
        outputData.put("documentType", docType);
        outputData.put("workflowNodeId", nodeId);
        outputData.put("documentVersion", document.getVersion());
        outputData.put("comment", request.getComment());
        outputData.put("submittedAt", LocalDateTime.now().toString());
        outputData.put("content", request.getContent());

        execution.setStatus(WorkflowInstanceStatusEnum.COMPLETED.getCode());
        execution.setOutputData(outputData);
        execution.setCompletedAt(LocalDateTime.now());
        workflowNodeExecutionMapper.updateById(execution);

        workflowNodeEngine.advanceWorkflow(instance.getId(), nodeId, outputData);
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
        Spec spec = entityValidator.requireExists(specMapper, specId, ResultCode.SPEC_NOT_FOUND);
        var instance = resolveWorkflowInstance(spec);
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

    private void requireLegacyMode(Spec spec) {
        if (spec != null && SpecLifecycleModeEnum.WORKFLOW.getCode().equals(spec.getLifecycleMode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "工作流模式 Spec 请在工作台中操作");
        }
    }

    private void requireWorkflowMode(Spec spec) {
        if (spec == null || !SpecLifecycleModeEnum.WORKFLOW.getCode().equals(spec.getLifecycleMode())) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "当前 Spec 未启用工作流模式");
        }
    }

    private void updateSpecStatus(String id, String status) {
        var updateEntity = new Spec();
        updateEntity.setId(id);
        updateEntity.setStatus(status);
        specMapper.updateById(updateEntity);
    }

    private void triggerWorkflow(Spec spec) {
        String requestId = UUID.randomUUID().toString();
        WorkflowTriggerMessage message = WorkflowTriggerMessage.builder()
                .specId(spec.getId())
                .docType("requirements")
                .triggerType("spec-review")
                .workflowTemplateId(spec.getWorkflowId())
                .triggerBy(SecurityUtil.getCurrentUserId())
                .tenantId(SecurityUtil.getCurrentTenantId())
                .triggeredAt(LocalDateTime.now())
                .requestId(requestId)
                .build();
        rabbitTemplate.convertAndSend("sf.workflow", "workflow.trigger.spec-review", message);
        log.info("需求审批通过后已发送工作流触发消息: specId={}, requestId={}", spec.getId(), requestId);
    }

    private SpecVO enrichWithDocuments(Spec spec) {
        var vo = specConverter.toVO(spec);
        var documents = specVersionHandler.getAllDocuments(spec.getId());
        vo.setDocuments(specDocumentConverter.toVOList(documents));
        return enrichSpecVOs(List.of(vo)).stream().findFirst().orElse(vo);
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

    private String resolveTargetBranch(String jiraTicket, String targetBranch, String specType) {
        if (isMarketingSpec(specType)) {
            return null;
        }
        if (StringUtils.hasText(targetBranch)) {
            return targetBranch.trim();
        }
        if (StringUtils.hasText(jiraTicket)) {
            return "feature/" + jiraTicket.trim().toUpperCase();
        }
        return null;
    }

    private void seedInitialRequirementsDocument(String specId, String description) {
        if (!StringUtils.hasText(description)) {
            return;
        }
        var request = new SpecDocumentRequest();
        request.setContent(description.trim());
        request.setChangeSummary("初始化需求文档");
        specVersionHandler.saveDocument(specId, "requirements", request);
    }

    private WorkflowInstanceVO createWorkflowInstance(Spec spec, SpecCreateRequest request) {
        WorkflowInstanceCreateRequest workflowRequest = new WorkflowInstanceCreateRequest();
        workflowRequest.setTemplateId(spec.getWorkflowId());
        workflowRequest.setSpecId(spec.getId());
        workflowRequest.setName(spec.getName() + " 工作流实例");
        workflowRequest.setVariables(buildWorkflowVariables(spec, request));
        WorkflowInstanceVO instance = workflowInstanceService.create(workflowRequest);

        Spec updateEntity = new Spec();
        updateEntity.setId(spec.getId());
        updateEntity.setWorkflowInstanceId(instance.getId());
        specMapper.updateById(updateEntity);

        return workflowInstanceService.getById(instance.getId());
    }

    private Map<String, Object> buildWorkflowVariables(Spec spec, SpecCreateRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("triggerType", "spec-workbench");
        variables.put("specName", spec.getName());
        variables.put("specDescription", spec.getDescription());
        variables.put("specType", spec.getSpecType());
        variables.put("profileData", spec.getProfileData());
        variables.put("workflowGoal", spec.getDescription());
        variables.put("jiraTicket", spec.getJiraTicket());
        variables.put("targetBranch", spec.getTargetBranch());
        variables.put("workspaceIds", request.getWorkspaceIds());
        if (!CollectionUtils.isEmpty(request.getWorkspaceIds())) {
            variables.put("workspaceId", request.getWorkspaceIds().get(0));
        } else if (StringUtils.hasText(request.getProjectId())) {
            variables.put("workspaceId", request.getProjectId());
        }
        return variables;
    }

    private String resolvePrimaryWorkspaceId(SpecCreateRequest request) {
        if (request == null) {
            return null;
        }
        if (!CollectionUtils.isEmpty(request.getWorkspaceIds())) {
            return request.getWorkspaceIds().get(0);
        }
        return request.getProjectId();
    }

    private List<SpecVO> enrichSpecVOs(List<SpecVO> specs) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        List<String> relatedUserIds = specs.stream()
                .flatMap(spec -> java.util.stream.Stream.of(spec.getOwner(), spec.getCreatedBy()))
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        Map<String, User> ownerMap = loadUserMap(relatedUserIds);
        Map<String, WorkflowTemplate> workflowMap = loadWorkflowTemplateMap(specs.stream()
                .map(SpecVO::getWorkflowId)
                .filter(StringUtils::hasText)
                .toList());
        Map<String, com.schemaplexai.model.vo.artifact.ArtifactVO> artifactMap = new LinkedHashMap<>();
        for (SpecVO spec : specs) {
            User owner = StringUtils.hasText(spec.getOwner()) ? ownerMap.get(spec.getOwner()) : null;
            if (owner != null) {
                spec.setOwnerName(StringUtils.hasText(owner.getRealName()) ? owner.getRealName() : owner.getUsername());
            }
            User creator = StringUtils.hasText(spec.getCreatedBy()) ? ownerMap.get(spec.getCreatedBy()) : null;
            if (creator != null) {
                spec.setCreatedByName(StringUtils.hasText(creator.getRealName()) ? creator.getRealName() : creator.getUsername());
            }
            WorkflowTemplate workflowTemplate = StringUtils.hasText(spec.getWorkflowId())
                    ? workflowMap.get(spec.getWorkflowId())
                    : null;
            if (workflowTemplate != null) {
                spec.setWorkflowName(workflowTemplate.getName());
            }
            if (StringUtils.hasText(spec.getPrimaryArtifactId())) {
                artifactMap.computeIfAbsent(spec.getPrimaryArtifactId(), artifactService::getById);
                spec.setPrimaryArtifact(artifactMap.get(spec.getPrimaryArtifactId()));
            }
        }
        return specs;
    }

    private String resolveSpecType(String specType) {
        return resolveSpecType(specType, "rd");
    }

    private String resolveSpecType(String specType, String fallback) {
        return StringUtils.hasText(specType) ? specType.trim().toLowerCase() : fallback;
    }

    private boolean isMarketingSpec(String specType) {
        return "marketing".equalsIgnoreCase(specType);
    }

    private Map<String, User> loadUserMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(User::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private Map<String, WorkflowTemplate> loadWorkflowTemplateMap(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return workflowTemplateMapper.selectBatchIds(new ArrayList<>(ids)).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(WorkflowTemplate::getId, item -> item, (left, right) -> left, LinkedHashMap::new));
    }

    private WorkflowInstance resolveWorkflowInstance(Spec spec) {
        if (spec == null) {
            return null;
        }
        if (StringUtils.hasText(spec.getWorkflowInstanceId())) {
            WorkflowInstance instance = workflowInstanceMapper.selectById(spec.getWorkflowInstanceId());
            if (instance != null) {
                return instance;
            }
        }
        return workflowInstanceMapper.selectOne(
                new LambdaQueryWrapper<WorkflowInstance>()
                        .eq(WorkflowInstance::getSpecId, spec.getId())
                        .orderByDesc(WorkflowInstance::getCreatedAt)
                        .last("LIMIT 1")
        );
    }

    private WorkflowInstance requireWorkflowInstance(Spec spec) {
        WorkflowInstance instance = resolveWorkflowInstance(spec);
        if (instance == null) {
            throw new BusinessException(ResultCode.WORKFLOW_INSTANCE_NOT_FOUND);
        }
        return instance;
    }

    private WorkflowInstance normalizeDraftWorkflowInstance(String instanceId) {
        if (!StringUtils.hasText(instanceId)) {
            return null;
        }
        WorkflowInstance instance = workflowInstanceMapper.selectById(instanceId);
        if (instance == null || !isAwaitingOriginalRequirement(instance)) {
            return instance;
        }

        long nodeExecutionCount = workflowNodeExecutionMapper.selectCount(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, instanceId)
        );
        boolean needsReset = nodeExecutionCount > 0
                || instance.getStartedAt() != null
                || instance.getCompletedAt() != null
                || StringUtils.hasText(instance.getCurrentNodeId())
                || !WorkflowInstanceStatusEnum.PENDING.getCode().equals(instance.getStatus());
        if (!needsReset) {
            return instance;
        }

        Map<String, Object> variables = new LinkedHashMap<>(instance.getVariables() == null ? Map.of() : instance.getVariables());
        variables.remove("securityDecision");
        variables.remove("securityPendingStart");

        workflowInstanceMapper.update(
                null,
                new UpdateWrapper<WorkflowInstance>()
                        .eq("id", instanceId)
                        .set("status", WorkflowInstanceStatusEnum.PENDING.getCode())
                        .set("current_node_id", null)
                        .set("started_at", null)
                        .set("completed_at", null)
                        .set("updated_at", LocalDateTime.now())
        );

        WorkflowInstance variablesUpdate = new WorkflowInstance();
        variablesUpdate.setId(instanceId);
        variablesUpdate.setVariables(variables);
        workflowInstanceMapper.updateById(variablesUpdate);

        workflowNodeExecutionMapper.delete(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, instanceId)
        );

        log.warn("检测到工作流实例在原始需求填写前被提前启动，已重置为待启动草稿态: instanceId={}", instanceId);
        return workflowInstanceMapper.selectById(instanceId);
    }

    private boolean isAwaitingOriginalRequirement(WorkflowInstance instance) {
        if (instance == null || instance.getVariables() == null) {
            return false;
        }
        String triggerType = str(instance.getVariables(), "triggerType");
        if (!"spec-workbench".equals(triggerType)) {
            return false;
        }
        String originalRequirement = str(instance.getVariables(), "originalRequirement");
        String specDescription = str(instance.getVariables(), "specDescription");
        return !StringUtils.hasText(originalRequirement) && !StringUtils.hasText(specDescription);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> requireNodeDef(WorkflowInstance instance, String nodeId) {
        if (instance == null || instance.getDefinition() == null) {
            throw new BusinessException(ResultCode.WORKFLOW_DEFINITION_INVALID);
        }
        Object nodes = instance.getDefinition().get("nodes");
        if (!(nodes instanceof List<?> nodeList)) {
            throw new BusinessException(ResultCode.WORKFLOW_DEFINITION_INVALID);
        }
        return nodeList.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(item -> nodeId.equals(str(item, "id")))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ResultCode.WORKFLOW_NODE_NOT_FOUND));
    }

    private void validateDocumentNode(Map<String, Object> nodeDef, String nodeId) {
        if (!NodeTypeEnum.DOCUMENT.getCode().equals(str(nodeDef, "type"))) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "节点不是文档节点: " + nodeId);
        }
    }

    private void validateCurrentWorkbenchNode(WorkflowInstance instance, String nodeId, String message) {
        if (instance == null || !StringUtils.hasText(nodeId)) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED, message);
        }
        if (!StringUtils.hasText(instance.getCurrentNodeId()) || !nodeId.equals(instance.getCurrentNodeId())) {
            throw new BusinessException(ResultCode.WORKFLOW_STATUS_NOT_ALLOWED, message);
        }
    }

    private WorkflowNodeExecution requireNodeExecution(String instanceId, String nodeId) {
        WorkflowNodeExecution execution = workflowNodeExecutionMapper.selectOne(
                new LambdaQueryWrapper<WorkflowNodeExecution>()
                        .eq(WorkflowNodeExecution::getInstanceId, instanceId)
                        .eq(WorkflowNodeExecution::getNodeId, nodeId)
                        .last("LIMIT 1")
        );
        if (execution == null) {
            throw new BusinessException(ResultCode.WORKFLOW_NODE_NOT_FOUND);
        }
        return execution;
    }

    private String resolveDocumentType(Map<String, Object> nodeDef, String nodeId) {
        String configuredDocumentType = resolveNodeDocumentType(getConfig(nodeDef));
        return StringUtils.hasText(configuredDocumentType) ? configuredDocumentType : nodeId;
    }

    private void appendDocumentVariables(WorkflowInstance instance, String docType, String content) {
        if (instance == null) {
            return;
        }
        Map<String, Object> variables = instance.getVariables() != null
                ? new LinkedHashMap<>(instance.getVariables()) : new LinkedHashMap<>();
        variables.put(docType + "Doc", content);
        instance.setVariables(variables);
        WorkflowInstance update = new WorkflowInstance();
        update.setId(instance.getId());
        update.setVariables(variables);
        update.setUpdatedAt(LocalDateTime.now());
        workflowInstanceMapper.updateById(update);
    }

    private void syncSpecWithWorkflow(String specId, WorkflowInstanceVO workflowInstance) {
        if (!StringUtils.hasText(specId) || workflowInstance == null) {
            return;
        }
        String currentNodeType = null;
        String currentNodeLabel = null;
        if (workflowInstance.getDefinition() != null && StringUtils.hasText(workflowInstance.getCurrentNodeId())) {
            Map<String, Object> currentNode = findNode(workflowInstance.getDefinition(), workflowInstance.getCurrentNodeId());
            if (currentNode != null) {
                currentNodeType = str(currentNode, "type");
                currentNodeLabel = str(currentNode, "label");
            }
        }
        specMapper.update(
                null,
                new UpdateWrapper<Spec>()
                        .eq("id", specId)
                        .set("workflow_instance_id", workflowInstance.getId())
                        .set("current_node_id", workflowInstance.getCurrentNodeId())
                        .set("current_node_type", currentNodeType)
                        .set("current_node_label", currentNodeLabel)
                        .set("workflow_status_snapshot", workflowInstance.getStatus())
                        .set("lifecycle_mode", SpecLifecycleModeEnum.WORKFLOW.getCode())
                        .set("status", resolveWorkflowDrivenSpecStatus(workflowInstance))
                        .set("updated_at", LocalDateTime.now())
        );
    }

    @SuppressWarnings("unchecked")
    private List<SpecWorkbenchNodeVO> buildWorkbenchNodes(WorkflowInstance instance, WorkflowInstanceVO workflowInstance) {
        if (instance.getDefinition() == null || workflowInstance == null) {
            return List.of();
        }
        Object rawNodes = instance.getDefinition().get("nodes");
        if (!(rawNodes instanceof List<?> definitionNodes)) {
            return List.of();
        }
        Map<String, WorkflowNodeExecution> executionMap = workflowNodeExecutionMapper.selectByInstanceId(instance.getId()).stream()
                .collect(Collectors.toMap(WorkflowNodeExecution::getNodeId, item -> item, (left, right) -> left, LinkedHashMap::new));

        return definitionNodes.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .map(node -> {
                    String nodeId = str(node, "id");
                    String nodeType = str(node, "type");
                    WorkflowNodeExecution execution = executionMap.get(nodeId);
                    SpecWorkbenchNodeVO vo = new SpecWorkbenchNodeVO();
                    vo.setNodeId(nodeId);
                    vo.setNodeType(nodeType);
                    vo.setNodeLabel(str(node, "label"));
                    vo.setStatus(execution != null ? execution.getStatus() : WorkflowInstanceStatusEnum.PENDING.getCode());
                    vo.setCurrent(nodeId.equals(workflowInstance.getCurrentNodeId()));
                    vo.setConfig(getConfig(node));
                    vo.setOutputData(execution != null ? execution.getOutputData() : null);
                    vo.setErrorMessage(execution != null ? execution.getErrorMessage() : null);
                    SpecDocument document = resolveWorkbenchDocument(instance.getSpecId(), nodeId, nodeType, getConfig(node), execution);
                    if (document != null) {
                        vo.setDocument(specDocumentConverter.toVO(document));
                    }
                    if (NodeTypeEnum.HUMAN_REVIEW.getCode().equals(nodeType)) {
                        ReviewSessionVO reviewSession = reviewSessionService.getLatestByWorkflowNode(instance.getId(), nodeId);
                        vo.setReviewSession(reviewSession);
                    }
                    return vo;
                })
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findNode(Map<String, Object> definition, String nodeId) {
        if (definition == null) {
            return null;
        }
        Object rawNodes = definition.get("nodes");
        if (!(rawNodes instanceof List<?> nodes)) {
            return null;
        }
        return nodes.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .filter(item -> nodeId.equals(str(item, "id")))
                .findFirst()
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getConfig(Map<String, Object> nodeDef) {
        if (nodeDef == null) {
            return Map.of();
        }
        Object config = nodeDef.get("config");
        return config instanceof Map<?, ?> ? (Map<String, Object>) config : Map.of();
    }

    private String str(Map<String, Object> source, String key) {
        if (source == null) {
            return null;
        }
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private SpecDocument resolveWorkbenchDocument(String specId,
                                                 String nodeId,
                                                 String nodeType,
                                                 Map<String, Object> config,
                                                 WorkflowNodeExecution execution) {
        if (!StringUtils.hasText(specId)) {
            return null;
        }
        if (NodeTypeEnum.DOCUMENT.getCode().equals(nodeType)) {
            SpecDocument document = specVersionHandler.getDocumentByNode(specId, nodeId);
            if (document != null) {
                return document;
            }
            String docType = resolveNodeDocumentType(config);
            if (StringUtils.hasText(docType)) {
                return specVersionHandler.getDocument(specId, docType);
            }
            return null;
        }
        if (NodeTypeEnum.AGENT.getCode().equals(nodeType)) {
            return resolveAgentWorkbenchDocument(specId, nodeId, config, execution);
        }
        if (NodeTypeEnum.HUMAN_REVIEW.getCode().equals(nodeType)) {
            String docType = resolveNodeDocumentType(config);
            if (StringUtils.hasText(docType)) {
                return specVersionHandler.getDocument(specId, docType);
            }
        }
        return null;
    }

    private SpecDocument resolveAgentWorkbenchDocument(String specId,
                                                       String nodeId,
                                                       Map<String, Object> config,
                                                       WorkflowNodeExecution execution) {
        if (execution == null || !WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(execution.getStatus())) {
            return null;
        }
        SpecDocument document = specVersionHandler.getDocumentByNode(specId, nodeId);
        if (isDocumentFreshForExecution(document, execution)) {
            return document;
        }
        String docType = resolveNodeDocumentType(config);
        if (!StringUtils.hasText(docType)) {
            return null;
        }
        document = specVersionHandler.getDocument(specId, docType);
        return isDocumentFreshForExecution(document, execution) ? document : null;
    }

    private boolean isDocumentFreshForExecution(SpecDocument document, WorkflowNodeExecution execution) {
        if (document == null) {
            return false;
        }
        if (execution == null || execution.getStartedAt() == null || document.getUpdatedAt() == null) {
            return true;
        }
        return !document.getUpdatedAt().isBefore(execution.getStartedAt());
    }

    private String resolveNodeDocumentType(Map<String, Object> config) {
        String artifactDocType = str(config, "artifactDocType");
        if (StringUtils.hasText(artifactDocType)) {
            return artifactDocType;
        }
        String documentKey = str(config, "documentKey");
        if (StringUtils.hasText(documentKey)) {
            return documentKey;
        }
        String documentType = str(config, "documentType");
        return StringUtils.hasText(documentType) ? documentType : null;
    }

    private String resolveWorkflowDrivenSpecStatus(WorkflowInstanceVO workflowInstance) {
        if (workflowInstance == null) {
            return SpecStatusEnum.DRAFT.getCode();
        }
        String workflowStatus = workflowInstance.getStatus();
        if (WorkflowInstanceStatusEnum.COMPLETED.getCode().equals(workflowStatus)) {
            return SpecStatusEnum.COMPLETED.getCode();
        }
        if (WorkflowInstanceStatusEnum.FAILED.getCode().equals(workflowStatus)
                || WorkflowInstanceStatusEnum.CANCELLED.getCode().equals(workflowStatus)) {
            return SpecStatusEnum.FAILED.getCode();
        }
        if (WorkflowInstanceStatusEnum.PENDING.getCode().equals(workflowStatus)
                && !StringUtils.hasText(workflowInstance.getCurrentNodeId())
                && workflowInstance.getStartedAt() == null) {
            return SpecStatusEnum.DRAFT.getCode();
        }
        return SpecStatusEnum.IN_PROGRESS.getCode();
    }
}
