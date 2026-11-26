package com.schemaplexai.service.rag.retrieve;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量实体匹配（不引入 NER 模型）。
 *
 * <p>目的不是"识别实体类型"，而是回答一个很具体的问题：这段 chunk 和 query 说的<b>是不是同一件事</b>。
 * 做法是把 query 与 chunk 都拆成"显著 token"集合，看交集占比：
 * <ul>
 *   <li>英文/数字：按非字母数字切分，保留长度 ≥ 2 的 token；</li>
 *   <li>中文：不做分词，取 2-gram（"逾期罚息" → 逾期/期罚/罚息），
 *       对短查询足够灵敏，又不会像单字那样全部命中。</li>
 * </ul>
 *
 * <p>刻意保守：query 抽不出 token 时<b>不过滤</b>，宁可多给几条片段，
 * 也不能把正确证据因为分词失败丢掉。
 */
@Component
public class EntityMatcher {

    /** 交集占比下限 */
    static final double MIN_OVERLAP_RATIO = 0.12D;
    /** 或者绝对交集数达到该值 */
    static final int MIN_SHARED_TOKENS = 2;

    public boolean hasOverlap(String query, String chunkText) {
        if (!StringUtils.hasText(query) || !StringUtils.hasText(chunkText)) {
            return true;
        }
        Set<String> queryTokens = tokens(query);
        if (queryTokens.isEmpty()) {
            return true;
        }
        Set<String> chunkTokens = tokens(chunkText);
        int shared = 0;
        for (String token : queryTokens) {
            if (chunkTokens.contains(token)) {
                shared++;
            }
        }
        if (shared == 0) {
            return false;
        }
        double ratio = shared / (double) queryTokens.size();
        return ratio >= MIN_OVERLAP_RATIO || shared >= MIN_SHARED_TOKENS;
    }

    /** 拆 token：ASCII 词 + 数字 + 中文 2-gram */
    public Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (!StringUtils.hasText(text)) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isAsciiWordChar(c)) {
                flushCjk(cjk, tokens);
                ascii.append(c);
            } else if (isCjk(c)) {
                flushAscii(ascii, tokens);
                cjk.append(c);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private void flushAscii(StringBuilder buffer, Set<String> tokens) {
        if (buffer.length() >= 2) {
            tokens.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private void flushCjk(StringBuilder buffer, Set<String> tokens) {
        if (buffer.length() == 0) {
            return;
        }
        if (buffer.length() == 1) {
            // 单字信息量太低，直接丢弃，避免"的""了"这类噪声把匹配拉满
            buffer.setLength(0);
            return;
        }
        for (int i = 0; i + 1 < buffer.length(); i++) {
            tokens.add(buffer.substring(i, i + 2));
        }
        if (buffer.length() >= 3) {
            tokens.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private boolean isAsciiWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.';
    }

    private boolean isCjk(char c) {
        return Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN;
    }

    /** 诊断用：交集 token */
    public Set<String> intersection(String query, String chunkText) {
        Set<String> queryTokens = new HashSet<>(tokens(query));
        queryTokens.retainAll(tokens(chunkText));
        return queryTokens;
    }
}
