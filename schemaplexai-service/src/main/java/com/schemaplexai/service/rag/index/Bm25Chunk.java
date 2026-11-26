package com.schemaplexai.service.rag.index;

/**
 * BM25 索引文档：与向量索引一一对应的 chunk。
 *
 * @param chunkId  全局唯一 chunk ID（与向量库共用，融合时用于去重）
 * @param tenantId 租户（硬过滤）
 * @param projectId 项目/上下文（硬过滤）
 * @param docId    文件 ID（版本更新时按此删除旧 chunk）
 * @param fileName 文件名（装配引用块用）
 * @param section  章节/sheet/代码单元
 * @param category 分类（code/document/excel/image）
 * @param text     原文（锚点 + 正文）
 * @param bm25Text BM25 专用文本（代码已做标识符拆分）
 */
public record Bm25Chunk(String chunkId,
                        String tenantId,
                        String projectId,
                        String docId,
                        String fileName,
                        String section,
                        String category,
                        String text,
                        String bm25Text) {
}
