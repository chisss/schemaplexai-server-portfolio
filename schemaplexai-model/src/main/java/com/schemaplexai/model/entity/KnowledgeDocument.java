package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识库文档管理实体
 */
@Data
@TableName(value = "sf_knowledge_document", autoResultMap = true)
public class KnowledgeDocument implements Serializable {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 租户 ID */
    private String tenantId;

    /** 关联的上下文 ID */
    private String contextId;

    /** 文档标题 */
    private String title;

    /** 原始文件名 */
    private String fileName;

    /** 文件类型: pdf/docx/xlsx/md/txt/html */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** MinIO 存储路径 */
    private String filePath;

    /** 状态: pending/processing/completed/failed */
    private String status;

    /** 分块数量 */
    private Integer chunkCount;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 使用的 Embedding 模型 */
    private String embeddingModel;

    /** 处理失败时的错误信息 */
    private String errorMessage;

    /** 扩展元数据 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    /** 创建人 */
    private String createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除 */
    @TableLogic
    private Integer deleted;
}
