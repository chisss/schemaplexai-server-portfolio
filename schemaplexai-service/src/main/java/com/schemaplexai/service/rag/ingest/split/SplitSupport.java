package com.schemaplexai.service.rag.ingest.split;

import com.schemaplexai.service.vector.EmbeddingTokenEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 切分公用工具：token 估算、句子切分、滑动窗口兜底。
 *
 * <p>token 估算直接复用 {@link EmbeddingTokenEstimator}（中文 1 字 ≈ 1 token，英文 4 字符 ≈ 1 token），
 * 保证"切分预算"与"向量化计费口径"一致，避免两边各算一套导致超限。
 */
public final class SplitSupport {

    /** 句子边界：中英文句号/问号/感叹号/分号 + 换行 */
    private static final String SENTENCE_DELIMITERS = "。！？；!?;\n";

    private SplitSupport() {
    }

    public static int tokens(String text) {
        return EmbeddingTokenEstimator.estimate(text);
    }

    public static boolean exceeds(String text, int budget) {
        return budget > 0 && tokens(text) > budget;
    }

    /** 按句子边界切分，保留分隔符 */
    public static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            current.append(c);
            if (SENTENCE_DELIMITERS.indexOf(c) >= 0) {
                String sentence = current.toString();
                if (!sentence.isBlank()) {
                    result.add(sentence);
                }
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            String tail = current.toString();
            if (!tail.isBlank()) {
                result.add(tail);
            }
        }
        return result;
    }

    /**
     * 硬切：单句超预算时按字符数切，保证不出现"一个 chunk 塞满整篇"的极端情况。
     * 中文按 1 字符 ≈ 1 token，英文按 4 字符 ≈ 1 token，这里取保守的 1 字符 = 1 token。
     */
    public static List<String> hardSplit(String text, int budget) {
        List<String> parts = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return parts;
        }
        int limit = Math.max(32, budget);
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + limit);
            parts.add(text.substring(start, end));
            start = end;
        }
        return parts;
    }

    /**
     * 用句子聚合到目标预算。超长句自动硬切，避免死循环。
     *
     * @param head     每个 chunk 都要带的前缀（如表头），不参与预算累计后再截断，但会占用预算
     * @param budget   目标 token 预算
     * @param overlap  重叠 token 数（按句子回退近似）
     */
    public static List<String> window(List<String> sentences, String head, int budget, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (sentences.isEmpty()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        List<String> currentSentences = new ArrayList<>();

        for (String sentence : sentences) {
            List<String> pieces = exceeds(sentence, budget) ? hardSplit(sentence, budget) : List.of(sentence);
            for (String piece : pieces) {
                String candidate = current + piece;
                if (exceeds(head + candidate, budget) && !currentSentences.isEmpty()) {
                    chunks.add(head + current);
                    List<String> carry = overlapTail(currentSentences, overlap);
                    current = new StringBuilder(String.join("", carry));
                    currentSentences = new ArrayList<>(carry);
                }
                current.append(piece);
                currentSentences.add(piece);
            }
        }
        if (!current.isEmpty() && !current.toString().isBlank()) {
            chunks.add(head + current);
        }
        return chunks;
    }

    private static List<String> overlapTail(List<String> sentences, int overlapTokens) {
        if (overlapTokens <= 0 || sentences.isEmpty()) {
            return List.of();
        }
        List<String> tail = new ArrayList<>();
        int accumulated = 0;
        for (int i = sentences.size() - 1; i >= 0; i--) {
            String sentence = sentences.get(i);
            accumulated += tokens(sentence);
            tail.add(0, sentence);
            if (accumulated >= overlapTokens) {
                break;
            }
        }
        return tail;
    }
}
