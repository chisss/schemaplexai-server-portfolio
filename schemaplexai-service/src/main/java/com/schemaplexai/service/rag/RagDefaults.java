package com.schemaplexai.service.rag;

/**
 * RAG v2 默认值与枚举常量。
 *
 * <p>集中一份，避免默认值散落在 service / VO / 迁移脚本三处对不齐。
 * 以《企业级私有化 RAG 设计方案（修订版）》的推荐区间为准：
 * 文档分块 500~700 token、融合池 20、RRF k=60、上下文最多 2 个文件。
 */
public final class RagDefaults {

    private RagDefaults() {
    }

    // ── 分块 ──
    /** 文档分块目标 token 数（500~700 区间的中值） */
    public static final int CHUNK_SIZE = 600;
    public static final int CHUNK_OVERLAP = 120;

    // ── 召回 ──
    public static final int BM25_TOP_K = 20;
    public static final int VECTOR_TOP_K = 20;
    /** 融合后进入精排/裁剪的候选池 */
    public static final int FUSION_TOP_N = 20;
    /** 最终进入上下文的片段数 */
    public static final int RETRIEVAL_TOP_K = 6;
    public static final double RETRIEVAL_MIN_SCORE = 0.55D;
    public static final int RRF_K = 60;

    // ── 精排 ──
    public static final int RERANK_TOP_N = 6;
    public static final double RERANK_MIN_SCORE = 0.0D;
    public static final int RERANK_TIMEOUT_MS = 1500;

    // ── 上下文装配 ──
    public static final int MAX_FILES_PER_CONTEXT = 2;
    public static final int CONTEXT_TOKEN_BUDGET = 4000;

    // ── 缓存 ──
    public static final int CACHE_TTL_SECONDS = 300;

    // ── 摄入 ──
    public static final int MAX_FILE_SIZE_MB = 50;
    public static final int OCR_MAX_CONCURRENCY = 2;
    public static final int OCR_MIN_TEXT_CHARS = 20;

    // ── 测评 ──
    public static final int EVAL_RECALL_K = 20;

    // ── 合规 ──
    public static final double FAITHFULNESS_THRESHOLD = 0.70D;

    // ── 枚举值 ──
    public static final String OCR_MODE_MULTIMODAL = "multimodal";
    public static final String OCR_MODE_API = "api";
    public static final String OCR_MODE_DISABLED = "disabled";

    public static final String RERANK_POLICY_ALWAYS = "always";
    public static final String RERANK_POLICY_AUTO = "auto";
    public static final String RERANK_POLICY_NEVER = "never";
}
