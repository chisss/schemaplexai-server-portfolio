package com.schemaplexai.service.rag.index;

/**
 * BM25 命中。
 *
 * @param rank 该路召回的排名（从 1 开始），RRF 只用排名不用分数
 */
public record Bm25Hit(String chunkId,
                      String docId,
                      String fileName,
                      String section,
                      String category,
                      String text,
                      float score,
                      int rank) {
}
