package com.schemaplexai.service.spec.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.SpecDocumentMapper;
import com.schemaplexai.dao.mapper.SpecVersionMapper;
import com.schemaplexai.model.dto.spec.SpecDocumentRequest;
import com.schemaplexai.model.entity.SpecDocument;
import com.schemaplexai.model.entity.SpecVersion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Spec版本处理器 — 文档保存 + 自动创建版本快照
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecVersionHandler {

    private final SpecDocumentMapper specDocumentMapper;
    private final SpecVersionMapper specVersionMapper;

    /**
     * 获取Spec指定类型的文档
     */
    public SpecDocument getDocument(String specId, String docType) {
        return specDocumentMapper.selectOne(
                new LambdaQueryWrapper<SpecDocument>()
                        .eq(SpecDocument::getSpecId, specId)
                        .eq(SpecDocument::getDocType, docType)
        );
    }

    /**
     * 获取 Spec 指定工作流节点的文档
     */
    public SpecDocument getDocumentByNode(String specId, String workflowNodeId) {
        return specDocumentMapper.selectOne(
                new LambdaQueryWrapper<SpecDocument>()
                        .eq(SpecDocument::getSpecId, specId)
                        .eq(SpecDocument::getWorkflowNodeId, workflowNodeId)
                        .last("LIMIT 1")
        );
    }

    /**
     * 获取Spec的所有文档
     */
    public List<SpecDocument> getAllDocuments(String specId) {
        return specDocumentMapper.selectList(
                new LambdaQueryWrapper<SpecDocument>()
                        .eq(SpecDocument::getSpecId, specId)
        );
    }

    /**
     * 保存或更新文档，并自动创建版本快照
     */
    public SpecDocument saveDocument(String specId, String docType, SpecDocumentRequest request) {
        return saveDocument(specId, docType, null, request);
    }

    /**
     * 保存或更新文档，并自动创建版本快照
     */
    public SpecDocument saveDocument(String specId, String docType, String workflowNodeId, SpecDocumentRequest request) {
        var existing = getDocument(specId, docType);
        if (StringUtils.hasText(workflowNodeId)) {
            existing = getDocumentByNode(specId, workflowNodeId);
        }

        if (existing != null) {
            // 更新已有文档
            var newVersion = existing.getVersion() + 1;
            existing.setContent(request.getContent());
            existing.setVersion(newVersion);
            if (StringUtils.hasText(workflowNodeId)) {
                existing.setWorkflowNodeId(workflowNodeId);
            }
            existing.setUpdatedBy(SecurityUtil.getCurrentUserId());
            existing.setUpdatedAt(LocalDateTime.now());
            specDocumentMapper.updateById(existing);

            // 创建版本快照
            createVersionSnapshot(specId, docType, request.getContent(),
                    newVersion, request.getChangeSummary(), workflowNodeId);

            log.info("更新Spec文档: specId={}, docType={}, workflowNodeId={}, version={}",
                    specId, docType, workflowNodeId, newVersion);
            return existing;
        }

        // 新建文档
        var document = new SpecDocument();
        document.setSpecId(specId);
        document.setDocType(docType);
        document.setContent(request.getContent());
        document.setVersion(1);
        document.setWorkflowNodeId(workflowNodeId);
        document.setCreatedBy(SecurityUtil.getCurrentUserId());
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedBy(SecurityUtil.getCurrentUserId());
        document.setUpdatedAt(LocalDateTime.now());
        specDocumentMapper.insert(document);

        // 创建初始版本快照
        createVersionSnapshot(specId, docType, request.getContent(),
                1, request.getChangeSummary(), workflowNodeId);

        log.info("创建Spec文档: specId={}, docType={}, workflowNodeId={}", specId, docType, workflowNodeId);
        return document;
    }

    /**
     * 获取版本历史
     */
    public List<SpecVersion> getVersionHistory(String specId, String docType) {
        return specVersionMapper.selectList(
                new LambdaQueryWrapper<SpecVersion>()
                        .eq(SpecVersion::getSpecId, specId)
                        .eq(SpecVersion::getDocType, docType)
                        .orderByDesc(SpecVersion::getVersionNumber)
        );
    }

    /**
     * 校验提交审批前文档必须存在
     */
    public void requireDocumentExists(String specId, String docType) {
        var document = getDocument(specId, docType);
        if (document == null) {
            throw new BusinessException(ResultCode.SPEC_DOCUMENT_NOT_FOUND);
        }
    }

    /**
     * 创建版本快照
     */
    private void createVersionSnapshot(String specId, String docType,
                                       String content, int versionNumber,
                                       String changeSummary, String workflowNodeId) {
        var version = new SpecVersion();
        version.setSpecId(specId);
        version.setDocType(docType);
        version.setContent(content);
        version.setVersionNumber(versionNumber);
        version.setChangeSummary(changeSummary);
        version.setWorkflowNodeId(workflowNodeId);
        version.setCreatedBy(SecurityUtil.getCurrentUserId());
        version.setCreatedAt(LocalDateTime.now());
        specVersionMapper.insert(version);
    }
}
