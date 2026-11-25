package com.schemaplexai.service.evaluation.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则评分器对抗用例：验证文本相似度评分的已知缺陷边界，
 * 为后续 LLM Judge 双通道提供对照基线（文档化既有行为，非标定期望）。
 */
class EvalMetricCalculatorTest {

    private final EvalMetricCalculator calculator = new EvalMetricCalculator();

    @Test
    void shouldScoreZeroWhenOutputEmpty() {
        assertThat(calculator.calculateScore("", "任意期望输出")).isZero();
        assertThat(calculator.calculateScore(null, "任意期望输出")).isZero();
    }

    @Test
    void shouldScoreEightyWhenExpectedEmptyButOutputPresent() {
        assertThat(calculator.calculateScore("有输出但没有期望", "")).isEqualTo(80);
    }

    @Test
    void shouldScoreZeroWhenBothEmpty() {
        assertThat(calculator.calculateScore("", "")).isZero();
    }

    @Test
    void shouldScoreFullWhenOutputContainsExpected() {
        assertThat(calculator.calculateScore("今天上海的天气是晴天", "天气是晴天")).isEqualTo(100);
    }

    @Test
    void shouldBeCaseAndPunctuationInsensitive() {
        assertThat(calculator.calculateScore("Hello, World!", "hello world")).isEqualTo(100);
    }

    @Test
    void shouldScorePartialTokenOverlapProportionally() {
        // 期望 3 个 token，输出覆盖 2 个 → 67 分（2/3*100）
        int score = calculator.calculateScore("sales growth 15 percent", "sales growth increase");
        assertThat(score).isEqualTo(67);
    }

    @Test
    void chineseTokenizationCollapsesIntoSingleToken() {
        // 中文无空格：归一化后整句是一个 token，包含即满分，否则落入低分档
        assertThat(calculator.calculateScore("预计销量一百台", "预计销量一百台")).isEqualTo(100);
        int unrelated = calculator.calculateScore("预计销量一百台", "库存周转率下降");
        assertThat(unrelated).isLessThan(50);
    }

    @Test
    void numericTrapCanBeMissedByTokenSubstringMatching() {
        // 已知缺陷：token 子串匹配会让"1000"匹配"100"，答案差一个数量级仍得 95 高分。
        // 该用例固化为回归对照——引入 LLM Judge 后由 Judge 分修正。
        int score = calculator.calculateScore("销售额 1000 万", "销售额 100 万");
        assertThat(score).isGreaterThanOrEqualTo(90);
    }
}
