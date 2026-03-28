package com.schemaplexai.model.vo.knowledge;

import lombok.Data;

import java.time.LocalDateTime;

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
    private String status;
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
