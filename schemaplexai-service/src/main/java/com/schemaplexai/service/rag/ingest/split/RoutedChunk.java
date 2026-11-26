package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.rag.ingest.IngestCategory;

/**
 * 切分产物：一个可入库的 chunk。
 *
 * <p>{@code text} 是最终入索引/入向量的文本（= 锚点 + 正文），
 * {@code body} 是去掉锚点的正文，供实体过滤与引用渲染时避免锚点污染；
 * {@code bm25Text} 是给 BM25 的专门文本（代码会做标识符拆分）。
 *
 * @param index     chunk 序号（同一文件内从 0 开始）
 * @param section   章节路径（文件 › 章 › 节），Excel 为 sheet 名
 * @param anchor    来源锚点文本
 * @param body      正文
 * @param text      锚点 + 正文
 * @param bm25Text  BM25 专用文本
 * @param category  来源分类
 */
public record RoutedChunk(int index,
                          String section,
                          String anchor,
                          String body,
                          String text,
                          String bm25Text,
                          IngestCategory category) {

    /** 锚点格式：便于用户与 LLM 判断"这段话来自哪份文件的哪一节" */
    public static String buildAnchor(String fileName, String section, int index, boolean anchorEnabled) {
        if (!anchorEnabled) {
            return "";
        }
        String safeFile = fileName == null || fileName.isBlank() ? "未命名文件" : fileName;
        String safeSection = section == null || section.isBlank() ? "正文" : section;
        return "【来源：" + safeFile + " › " + safeSection + " › #" + index + "】";
    }

    public static RoutedChunk create(int index, String section, String anchor, String body,
                                     String bm25Text, IngestCategory category) {
        String text = anchor == null || anchor.isBlank() ? body : anchor + "\n" + body;
        return new RoutedChunk(index, section, anchor, body, text, bm25Text, category);
    }
}
