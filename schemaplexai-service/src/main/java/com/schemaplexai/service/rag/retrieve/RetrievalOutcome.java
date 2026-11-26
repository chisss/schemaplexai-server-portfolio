package com.schemaplexai.service.rag.retrieve;

import java.util.List;

/**
 * 检索结果。
 *
 * <p>{@code contextBlock} 是可直接拼进 Prompt 的引用块：每条片段带 {@code [n]} 编号与来源锚点，
 * 让模型回答时能标注出处，也让"这句话到底来自哪个文件"可回溯。
 *
 * @param chunks       进入上下文的片段（按最终分数降序）
 * @param contextBlock 已编号的引用块文本
 * @param trace        链路埋点（延迟、降级、丢弃原因）
 */
public record RetrievalOutcome(List<RetrievalCandidate> chunks, String contextBlock, RetrievalTrace trace) {

    public static RetrievalOutcome empty(RetrievalTrace trace) {
        return new RetrievalOutcome(List.of(), "", trace);
    }

    public boolean isEmpty() {
        return chunks == null || chunks.isEmpty();
    }
}
