package com.schemaplexai.service.knowledge;

import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识文档管理服务
 */
public interface KnowledgeDocumentService {

    /**
     * 上传文档并启动 RAG 摄入管线
     *
     * @param contextId 关联的上下文 ID
     * @param file      上传的文件
     * @return 文档记录 VO
     */
    KnowledgeDocumentVO uploadDocument(String contextId, MultipartFile file);

    /**
     * 查询上下文下的知识文档列表
     *
     * @param contextId 上下文 ID
     * @return 文档记录列表
     */
    List<KnowledgeDocumentVO> listByContextId(String contextId);

    /**
     * 查询文档处理状态
     *
     * @param documentId 文档 ID
     * @return 文档记录 VO
     */
    KnowledgeDocumentVO getDocumentStatus(String documentId);
}
