package com.schemaplexai.service.knowledge.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.FilenameSanitizer;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.dto.knowledge.UploadDocumentRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import com.schemaplexai.service.knowledge.KnowledgeDocumentAuditWriter;
import com.schemaplexai.service.knowledge.KnowledgeDocumentAuditWriter.KnowledgeAuditEvent;
import com.schemaplexai.service.knowledge.KnowledgeDocumentService;
import com.schemaplexai.service.memory.rag.DocumentIngestionService;
import com.schemaplexai.service.storage.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 知识文档管理服务实现（v2）。
 *
 * <p>上传流程：校验 → SHA256 去重 → MinIO 落盘 → DB 写入 → afterCommit 异步摄入 → 审计双写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final Tika TIKA = new Tika();

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentIngestionService documentIngestionService;
    private final DocumentStorageService documentStorageService;
    private final KnowledgeDocumentAuditWriter auditWriter;
    private final TaskExecutor taskExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVO uploadDocument(String contextId, MultipartFile file,
                                              UploadDocumentRequest request,
                                              SecurityAuditContext auditContext) {
        if (file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能为空");
        }

        String tenantId = SecurityUtil.getCurrentTenantId();
        String userId = SecurityUtil.getCurrentUserId();
        String username = SecurityUtil.getCurrentUsername();
        String rawFileName = file.getOriginalFilename();
        String sanitizedName = FilenameSanitizer.sanitize(rawFileName);
        String fileType = FilenameSanitizer.extractExtension(rawFileName);
        long fileSize = file.getSize();
        String uploadChannel = StringUtils.hasText(request.getUploadChannel()) ? request.getUploadChannel() : "web";

        // ① 读取字节 + 计算 SHA256 + MIME 嗅探
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取上传文件失败");
        }
        String contentSha256 = HashUtils.sha256Hex(fileBytes);
        String mimeType = TIKA.detect(fileBytes, rawFileName);

        // ② 租户级 SHA256 去重
        KnowledgeDocument existing = documentMapper.selectOne(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getTenantId, tenantId)
                .eq(KnowledgeDocument::getContentSha256, contentSha256)
                .eq(KnowledgeDocument::getDeleted, 0)
                .last("LIMIT 1"));
        if (existing != null) {
            log.info("文档内容去重命中: tenantId={}, sha256={}, existingDocId={}", tenantId, contentSha256, existing.getId());
            auditWriter.record(KnowledgeAuditEvent.builder()
                    .tenantId(tenantId)
                    .eventType(SecurityComplianceConstant.EVENT_KB_UPLOAD_DEDUPLICATED)
                    .action(SecurityComplianceConstant.ACTION_KB_DOC_UPLOAD)
                    .eventTitle("文档内容去重")
                    .eventDetail("SHA256=" + contentSha256 + ", existingDocId=" + existing.getId())
                    .eventStatus(SecurityComplianceConstant.AUDIT_STATUS_WARNING)
                    .riskLevel(SecurityComplianceConstant.RISK_LEVEL_LOW)
                    .documentId(existing.getId())
                    .fileName(sanitizedName)
                    .contentSha256(contentSha256)
                    .auditContext(auditContext)
                    .build());
            return toVO(existing);
        }

        // ③ MinIO 流式上传
        String bucket = documentStorageService.getDefaultBucket();
        String documentId = null;
        var document = new KnowledgeDocument();
        document.setTenantId(tenantId);
        document.setContextId(contextId);
        document.setTitle(StringUtils.hasText(request.getTitle()) ? request.getTitle() : sanitizedName);
        document.setFileName(sanitizedName);
        document.setFileType(fileType);
        document.setFileSize(fileSize);
        document.setStatus("pending");
        document.setContentSha256(contentSha256);
        document.setMimeType(mimeType);
        document.setBucket(bucket);
        document.setUploadChannel(uploadChannel);
        document.setRetryCount(0);
        document.setCreatedBy(userId);
        if (request.getMetadata() != null) {
            document.setMetadata(request.getMetadata());
        }
        documentMapper.insert(document);
        documentId = document.getId();

        // 生成 objectKey 并上传
        String objectKey = documentStorageService.buildKnowledgeObjectKey(tenantId, documentId, sanitizedName);
        document.setObjectKey(objectKey);
        document.setFilePath(bucket + "/" + objectKey);
        documentMapper.updateById(document);

        try (InputStream in = new ByteArrayInputStream(fileBytes)) {
            documentStorageService.putObject(bucket, objectKey, in, fileSize, mimeType);
        } catch (Exception e) {
            log.error("MinIO 上传失败: documentId={}, error={}", documentId, e.getMessage(), e);
            throw new BusinessException(ResultCode.FAIL, "文件存储失败");
        }

        // ④ 审计：上传已接受
        String finalDocumentId = documentId;
        auditWriter.record(KnowledgeAuditEvent.builder()
                .tenantId(tenantId)
                .eventType(SecurityComplianceConstant.EVENT_KB_UPLOAD_ACCEPTED)
                .action(SecurityComplianceConstant.ACTION_KB_DOC_UPLOAD)
                .eventTitle("知识文档上传成功")
                .eventDetail("fileName=" + sanitizedName + ", size=" + fileSize + ", mimeType=" + mimeType)
                .documentId(finalDocumentId)
                .fileName(sanitizedName)
                .contentSha256(contentSha256)
                .auditContext(auditContext)
                .metadata(Map.of("fileType", fileType, "uploadChannel", uploadChannel))
                .build());

        // ⑤ afterCommit 异步触发摄入（确保事务已提交后再执行）
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                taskExecutor.execute(() -> {
                    try {
                        documentIngestionService.ingestDocument(finalDocumentId);
                    } catch (Exception e) {
                        log.error("异步摄入文档失败: documentId={}, error={}", finalDocumentId, e.getMessage(), e);
                    }
                });
            }
        });

        return toVO(document);
    }

    @Override
    public List<KnowledgeDocumentVO> listByContextId(String contextId) {
        return documentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getContextId, contextId)
                        .orderByDesc(KnowledgeDocument::getCreatedAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    @Override
    public KnowledgeDocumentVO getDocumentStatus(String documentId) {
        var document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        return toVO(document);
    }

    @Override
    public String getDownloadUrl(String documentId) {
        var document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        if (!StringUtils.hasText(document.getBucket()) || !StringUtils.hasText(document.getObjectKey())) {
            throw new BusinessException(ResultCode.FAIL, "文档尚未完成存储");
        }
        return documentStorageService.getPresignedDownloadUrl(document.getBucket(), document.getObjectKey(), 0);
    }

    private KnowledgeDocumentVO toVO(KnowledgeDocument entity) {
        var vo = new KnowledgeDocumentVO();
        vo.setId(entity.getId());
        vo.setContextId(entity.getContextId());
        vo.setTitle(entity.getTitle());
        vo.setFileName(entity.getFileName());
        vo.setFileType(entity.getFileType());
        vo.setFileSize(entity.getFileSize());
        vo.setStatus(entity.getStatus());
        vo.setChunkCount(entity.getChunkCount());
        vo.setTotalTokens(entity.getTotalTokens());
        vo.setEmbeddingModel(entity.getEmbeddingModel());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setContentSha256(entity.getContentSha256());
        vo.setMimeType(entity.getMimeType());
        vo.setUploadChannel(entity.getUploadChannel());
        vo.setCreatedBy(entity.getCreatedBy());
        vo.setContentWarnings(entity.getContentWarnings());
        vo.setRetryCount(entity.getRetryCount());
        vo.setMetadata(entity.getMetadata());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}