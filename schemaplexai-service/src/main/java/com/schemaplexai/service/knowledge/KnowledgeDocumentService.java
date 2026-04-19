package com.schemaplexai.service.knowledge;

import com.schemaplexai.model.dto.knowledge.UploadDocumentRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识文档管理服务
 */
public interface KnowledgeDocumentService {

    /**
     * 上传文档并启动 RAG 摄入管线（v2：MinIO 落盘 + SHA256 去重 + 安全审计）。
     *
     * @param contextId    关联的上下文 ID
     * @param file         上传的文件
     * @param request      上传请求元数据（幂等键、uploadChannel、自定义标题等）
     * @param auditContext 安全审计上下文（IP/UA）
     * @return 文档记录 VO
     */
    KnowledgeDocumentVO uploadDocument(String contextId, MultipartFile file,
                                       UploadDocumentRequest request,
                                       SecurityAuditContext auditContext);

    /**
     * 查询上下文下的知识文档列表
     */
    List<KnowledgeDocumentVO> listByContextId(String contextId);

    /**
     * 查询文档处理状态
     */
    KnowledgeDocumentVO getDocumentStatus(String documentId);

    /**
     * 生成文档下载预签名 URL。
     */
    String getDownloadUrl(String documentId);
}
