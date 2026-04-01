package com.schemaplexai.service.vector;

import org.springframework.util.StringUtils;

/**
 * 向量请求 Tokens 粗略估算器
 */
public final class EmbeddingTokenEstimator {

    private EmbeddingTokenEstimator() {
    }

    public static int estimate(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        int chineseCount = 0;
        int otherCount = 0;
        for (char c : content.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                chineseCount++;
            } else {
                otherCount++;
            }
        }
        return chineseCount + (otherCount / 4) + 1;
    }
}
