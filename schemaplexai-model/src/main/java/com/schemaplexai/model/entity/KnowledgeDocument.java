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
import java.util.List;
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

    /** 对象逻辑展示路径（一般等于 {bucket}/{objectKey}） */
    private String filePath;

    /** 状态: pending/processing/scanning/completed/failed/blocked */
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

    /** 文件内容 SHA256（hex），用于租户级去重 */
    private String contentSha256;

    /** MIME 类型（Tika 嗅探） */
    private String mimeType;

    /** MinIO bucket */
    private String bucket;

    /** MinIO object key */
    private String objectKey;

    /** 上传来源: web/api/mcp/import */
    private String uploadChannel;

    /** 内容安全扫描命中的 warning 规则列表 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> contentWarnings;

    /** 摄入重试次数 */
    private Integer retryCount;

    /** 下次重试时间 */
    private LocalDateTime nextRetryAt;

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
