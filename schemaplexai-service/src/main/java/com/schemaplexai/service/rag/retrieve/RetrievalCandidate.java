package com.schemaplexai.service.rag.retrieve;

/**
 * 召回候选（双路合并后的统一形态）。
 *
 * <p>把"来自哪一路、排第几、得了多少分"全部保留下来，是因为排查召回质量问题
 * 时必须能回答："这条是 BM25 找到的还是向量找到的？在哪一路排第几？"
 * 只留一个融合分数会让线上问题无法归因。
 *
 * @param chunkId     与向量库共用的 chunk ID
 * @param docId       文件 ID
 * @param fileName    文件名
 * @param section     章节/sheet/代码单元
 * @param category    分类
 * @param text        原文（含锚点）
 * @param bm25Rank    BM25 排名（未命中为 null）
 * @param vectorRank  向量排名（未命中为 null）
 * @param bm25Score   BM25 原始分数（仅诊断用，不参与融合）
 * @param vectorScore 向量相似度
 * @param rrfScore    RRF 融合分
 * @param rerankScore 精排分（未精排为 null）
 * @param matchedBy   命中来源标记，如 bm25+vector / bm25 / vector
 */
public record RetrievalCandidate(String chunkId,
                                 String docId,
                                 String fileName,
                                 String section,
                                 String category,
                                 String text,
                                 Integer bm25Rank,
                                 Integer vectorRank,
                                 Double bm25Score,
                                 Double vectorScore,
                                 double rrfScore,
                                 Double rerankScore,
                                 String matchedBy) {

    public RetrievalCandidate withRrfScore(double newRrfScore) {
        return new RetrievalCandidate(chunkId, docId, fileName, section, category, text,
                bm25Rank, vectorRank, bm25Score, vectorScore, newRrfScore, rerankScore, matchedBy);
    }

    public RetrievalCandidate withRerankScore(Double newRerankScore) {
        return new RetrievalCandidate(chunkId, docId, fileName, section, category, text,
                bm25Rank, vectorRank, bm25Score, vectorScore, rrfScore, newRerankScore, matchedBy);
    }

    /** 最终排序分：有精排分用精排分，否则用 RRF 分 */
    public double effectiveScore() {
        return rerankScore != null ? rerankScore : rrfScore;
    }

    public boolean hitByBoth() {
        return bm25Rank != null && vectorRank != null;
    }
}
