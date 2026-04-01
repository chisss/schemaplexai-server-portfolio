package com.schemaplexai.service.knowledge.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.KnowledgeDocumentMapper;
import com.schemaplexai.model.entity.KnowledgeDocument;
import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import com.schemaplexai.service.knowledge.KnowledgeDocumentService;
import com.schemaplexai.service.memory.rag.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.List;

/**
 * 知识文档管理服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeDocumentMapper documentMapper;
    private final DocumentIngestionService documentIngestionService;
    private final TaskExecutor taskExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentVO uploadDocument(String contextId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件不能为空");
        }

        String fileName = file.getOriginalFilename();
        String fileType = extractFileType(fileName);
        long fileSize = file.getSize();

        var document = new KnowledgeDocument();
        document.setTenantId(SecurityUtil.getCurrentTenantId());
        document.setContextId(contextId);
        document.setTitle(fileName);
        document.setFileName(fileName);
        document.setFileType(fileType);
        document.setFileSize(fileSize);
        document.setStatus("pending");
        document.setCreatedBy(SecurityUtil.getCurrentUserId());
        documentMapper.insert(document);

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取上传文件失败");
        }
        String originalFilename = file.getOriginalFilename();
        taskExecutor.execute(() -> ingestDocumentAsync(document.getId(), document.getTenantId(), contextId, fileBytes, originalFilename));

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

    protected void ingestDocumentAsync(String documentId, String tenantId, String contextId, byte[] fileBytes, String fileName) {
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            documentIngestionService.ingestDocument(
                    documentId, tenantId, contextId, inputStream, fileName
            );
        } catch (Exception e) {
            log.error("异步摄入文档失败: documentId={}, error={}", documentId, e.getMessage(), e);
        }
    }

    @Override
    public KnowledgeDocumentVO getDocumentStatus(String documentId) {
        var document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "文档不存在");
        }
        return toVO(document);
    }

    private String extractFileType(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
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
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
