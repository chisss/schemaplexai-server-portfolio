package com.schemaplexai.model.vo.knowledge;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 知识文档 VO
 */
@Data
public class KnowledgeDocumentVO {

    private String id;
    private String contextId;
    private String title;
    private String fileName;
    private String fileType;
    private Long fileSize;
    /** pending / processing / scanning / completed / failed / blocked */
    private String status;
    private Integer chunkCount;
    private Integer totalTokens;
    private String embeddingModel;
    private String errorMessage;

    /** 文件 SHA256（hex），支持前端展示/Copy */
    private String contentSha256;

    /** MIME 类型 */
    private String mimeType;

    /** 上传来源: web/api/mcp/import */
    private String uploadChannel;

    /** 上传人 ID */
    private String createdBy;

    /** 上传人用户名（冗余，供前端直接展示） */
    private String createdByName;

    /** 内容安全扫描命中的 warning 规则列表 */
    private List<Map<String, Object>> contentWarnings;

    /** 重试次数 */
    private Integer retryCount;

    /** 扩展元数据 */
    private Map<String, Object> metadata;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
