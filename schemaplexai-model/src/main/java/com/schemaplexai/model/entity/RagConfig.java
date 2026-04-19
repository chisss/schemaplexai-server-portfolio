package com.schemaplexai.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RAG 配置实体
 */
@Data
@TableName("sf_rag_config")
public class RagConfig implements Serializable {

    @TableId
    private String tenantId;

    /** 是否启用 RAG */
    private Boolean enabled;

    /** 向量模型来源: builtin（内置ONNX）/ api（外部API） */
    private String embeddingSource;

    /** 内置 ONNX 向量模型标识，如 all-MiniLM-L6-v2、bge-small-en-v1.5 */
    private String builtinEmbeddingModelId;

    /** 绑定的外部向量模型配置 ID（sf_ai_model.id） */
    private String vectorModelId;

    /** 向量集合名称 */
    private String collectionName;

    /** 文本分块大小 */
    private Integer chunkSize;

    /** 分块重叠大小 */
    private Integer chunkOverlap;

    /** 检索 TopK */
    private Integer retrievalTopK;

    /** 检索最小分数 */
    private Double retrievalMinScore;

    /** 向量维度（可用于降维/强制指定） */
    private Integer embeddingDimension;

    /** 是否启用文本清洗 */
    private Boolean textCleaningEnabled;

    /** 是否启用 Reranker 二阶段精排 */
    private Boolean rerankerEnabled;

    /** Reranker 模型来源: builtin（内置ONNX）/ api（外部API，预留） */
    private String rerankerSource;

    /** Reranker 精排后返回的最大数量 */
    private Integer rerankerTopN;

    /** Reranker 精排最小评分阈值 */
    private Double rerankerMinScore;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
