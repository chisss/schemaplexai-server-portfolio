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

    // ===== v2：摄入与解析 =====

    /** 单文件最大体积（MB） */
    private Integer maxFileSizeMb;

    /** 是否允许摄入代码文件（AST 切分） */
    private Boolean ingestCodeEnabled;

    /** 是否允许摄入 Excel（POI 转 Markdown 表格） */
    private Boolean ingestExcelEnabled;

    /** 扫描件/图片 OCR 模式：multimodal（平台多模态）/ api（外部 OCR）/ disabled */
    private String ocrMode;

    /** OCR 使用的多模态模型 ID（sf_ai_model.id） */
    private String ocrModelId;

    /** OCR 解析并发上限（与问答推理资源隔离） */
    private Integer ocrMaxConcurrency;

    /** OCR 结果最小字符数，低于该值判定解析失败 */
    private Integer ocrMinTextChars;

    // ===== v2：切分策略 =====

    /** 按章节语义边界切分 */
    private Boolean sectionSplitEnabled;

    /** 代码按 AST（类/方法）切分 */
    private Boolean codeAstSplitEnabled;

    /** Excel 按 sheet + 表格块切分 */
    private Boolean tableBlockSplitEnabled;

    /** chunk 头部注入来源锚点 */
    private Boolean chunkAnchorEnabled;

    // ===== v2：索引与召回 =====

    /** 是否启用 BM25（Lucene）索引与召回 */
    private Boolean bm25Enabled;

    /** BM25 单路召回数量 */
    private Integer bm25TopK;

    /** 向量单路召回数量 */
    private Integer vectorTopK;

    /** 是否强制前置租户/项目过滤（审计用，恒为 true） */
    private Boolean prefilterRequired;

    // ===== v2：融合与重排 =====

    /** RRF 平滑常数 k */
    private Integer rrfK;

    /** 融合后候选池大小 */
    private Integer fusionTopN;

    /** Rerank 门禁策略：always / auto / never */
    private String rerankPolicy;

    /** Rerank 硬超时（毫秒），超时回落 RRF 原序 */
    private Integer rerankTimeoutMs;

    /** 进入上下文的文件数量上限 */
    private Integer maxFilesPerContext;

    /** 是否启用 query 实体与 chunk 实体交集过滤 */
    private Boolean entityOverlapFilterEnabled;

    /** 上下文 Token 硬上限 */
    private Integer contextTokenBudget;

    /** 简单关键词查询是否跳过向量召回（BM25 快通道） */
    private Boolean keywordFastPathEnabled;

    // ===== v2：检索缓存 =====

    /** 是否启用检索结果缓存 */
    private Boolean retrievalCacheEnabled;

    /** 检索缓存 TTL（秒） */
    private Integer retrievalCacheTtlSeconds;

    // ===== v2：生成与合规 =====

    /** 是否强制回答标注引用编号 */
    private Boolean citationRequired;

    /** 忠实度低时是否转 HITL 人工复核 */
    private Boolean hitlOnLowFaithfulness;

    /** 忠实度阈值 */
    private Double faithfulnessThreshold;

    /** 评测 Recall@K 的 K */
    private Integer evalRecallK;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
