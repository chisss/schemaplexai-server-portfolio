package com.schemaplexai.service.knowledge.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.constant.DocumentIngestionConstant;
import com.schemaplexai.common.constant.SecurityComplianceConstant;
import com.schemaplexai.common.enums.KnowledgeDocumentStatusEnum;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.FilenameSanitizer;
import com.schemaplexai.common.util.HashUtils;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.converter.KnowledgeDocumentConverter;
import com.schemaplexai.model.dto.knowledge.UploadDocumentRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import com.schemaplexai.service.knowledge.KnowledgeDocumentAssembler;
import com.schemaplexai.service.knowledge.KnowledgeDocumentAuditWriter;
import com.schemaplexai.service.knowledge.KnowledgeDocumentAuditWriter.KnowledgeAuditEvent;
import com.schemaplexai.service.knowledge.KnowledgeDocumentService;
import com.schemaplexai.service.memory.rag.DocumentIngestionService;
import com.schemaplexai.service.storage.DocumentStorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.beans.factory.annotation.Qualifier;
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
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 知识文档管理服务实现（v2）。
 *
 * <p>上传流程：校验 → SHA256 去重 → MinIO 落盘 → DB 写入 → afterCommit 异步摄入 → 审计双写。
 */
@Slf4j
@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private static final Tika TIKA = new Tika();

    /** 允许上传的 MIME 类型白名单 */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "text/plain",
            "text/markdown",
            "text/html",
            "text/csv"
    );

    /** 单文件最大 50MB */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentIngestionService documentIngestionService;
    private final DocumentStorageService documentStorageService;
    private final KnowledgeDocumentAuditWriter auditWriter;
    private final KnowledgeDocumentConverter converter;
    private final KnowledgeDocumentAssembler assembler;
    private final Executor knowledgeIngestionExecutor;

    public KnowledgeDocumentServiceImpl(
            KnowledgeDocumentMapper documentMapper,
            DocumentIngestionService documentIngestionService,
            DocumentStorageService documentStorageService,
            KnowledgeDocumentAuditWriter auditWriter,
            KnowledgeDocumentConverter converter,
            KnowledgeDocumentAssembler assembler,
            @Qualifier("knowledgeIngestionExecutor") Executor knowledgeIngestionExecutor) {
        this.documentMapper = documentMapper;
        this.documentIngestionService = documentIngestionService;
        this.documentStorageService = documentStorageService;
        this.auditWriter = auditWriter;
        this.converter = converter;
        this.assembler = assembler;
        this.knowledgeIngestionExecutor = knowledgeIngestionExecutor;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVO uploadDocument(String contextId, MultipartFile file,
                                              UploadDocumentRequest request,
                                              SecurityAuditContext auditContext) {
        if (file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能为空");
        }

        long fileSize = file.getSize();
        if (fileSize > MAX_FILE_SIZE) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件大小超过限制（最大 50MB）");
        }

        String tenantId = SecurityUtil.getCurrentTenantId();
        String userId = SecurityUtil.getCurrentUserId();
        String rawFileName = file.getOriginalFilename();
        String sanitizedName = FilenameSanitizer.sanitize(rawFileName);
        String fileType = FilenameSanitizer.extractExtension(rawFileName);
        String uploadChannel = StringUtils.hasText(request.getUploadChannel())
                ? request.getUploadChannel() : DocumentIngestionConstant.DEFAULT_UPLOAD_CHANNEL;

        // ① 读取字节 + 计算 SHA256 + MIME 嗅探
        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取上传文件失败");
        }
        String contentSha256 = HashUtils.sha256Hex(fileBytes);
        String mimeType = TIKA.detect(fileBytes, rawFileName);

        // MIME 类型白名单校验
        if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST,
                    "不支持的文件类型: " + mimeType);
        }

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
            return converter.toVO(existing);
        }

        // ③ 组装实体 + MinIO 流式上传
        String bucket = documentStorageService.getDefaultBucket();
        var document = assembler.assemble(tenantId, contextId, userId, sanitizedName,
                fileType, fileSize, contentSha256, mimeType, bucket, uploadChannel, request);
        documentMapper.insert(document);
        String documentId = document.getId();

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
        auditWriter.record(KnowledgeAuditEvent.builder()
                .tenantId(tenantId)
                .eventType(SecurityComplianceConstant.EVENT_KB_UPLOAD_ACCEPTED)
                .action(SecurityComplianceConstant.ACTION_KB_DOC_UPLOAD)
                .eventTitle("知识文档上传成功")
                .eventDetail("fileName=" + sanitizedName + ", size=" + fileSize + ", mimeType=" + mimeType)
                .documentId(documentId)
                .fileName(sanitizedName)
                .contentSha256(contentSha256)
                .auditContext(auditContext)
                .metadata(Map.of("fileType", fileType, "uploadChannel", uploadChannel))
                .build());

        // ⑤ afterCommit 异步触发摄入
        String finalDocumentId = documentId;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                knowledgeIngestionExecutor.execute(() -> {
                    try {
                        documentIngestionService.ingestDocument(finalDocumentId);
                    } catch (Exception e) {
                        log.error("异步摄入文档失败: documentId={}, error={}", finalDocumentId, e.getMessage(), e);
                    }
                });
            }
        });

        return converter.toVO(document);
    }

    @Override
    public List<KnowledgeDocumentVO> listByContextId(String contextId) {
        List<KnowledgeDocument> documents = documentMapper.selectList(
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .eq(KnowledgeDocument::getContextId, contextId)
                        .orderByDesc(KnowledgeDocument::getCreatedAt));
        return converter.toVOList(documents);
    }

    @Override
    public KnowledgeDocumentVO getDocumentStatus(String documentId) {
        var document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        return converter.toVO(document);
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
}
