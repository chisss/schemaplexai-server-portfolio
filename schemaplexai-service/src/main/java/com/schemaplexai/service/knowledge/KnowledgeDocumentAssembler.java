package com.schemaplexai.service.knowledge;

import com.schemaplexai.common.constant.DocumentIngestionConstant;
import com.schemaplexai.common.enums.KnowledgeDocumentStatusEnum;
import com.schemaplexai.model.dto.knowledge.UploadDocumentRequest;
import com.schemaplexai.model.entity.KnowledgeDocument;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 知识文档实体组装器
 */
@Component
public class KnowledgeDocumentAssembler {

    public KnowledgeDocument assemble(String tenantId, String contextId, String userId,
                                      String sanitizedName, String fileType, long fileSize,
                                      String contentSha256, String mimeType, String bucket,
                                      String uploadChannel, UploadDocumentRequest request) {
        var doc = new KnowledgeDocument();
        doc.setTenantId(tenantId);
        doc.setContextId(contextId);
        doc.setTitle(StringUtils.hasText(request.getTitle()) ? request.getTitle() : sanitizedName);
        doc.setFileName(sanitizedName);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setStatus(KnowledgeDocumentStatusEnum.PENDING.getCode());
        doc.setContentSha256(contentSha256);
        doc.setMimeType(mimeType);
        doc.setBucket(bucket);
        doc.setUploadChannel(StringUtils.hasText(uploadChannel)
                ? uploadChannel : DocumentIngestionConstant.DEFAULT_UPLOAD_CHANNEL);
        doc.setRetryCount(0);
        doc.setCreatedBy(userId);
        if (request.getMetadata() != null) {
            doc.setMetadata(request.getMetadata());
        }
        return doc;
    }
}
