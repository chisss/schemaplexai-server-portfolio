package com.schemaplexai.service.rag.eval;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 接地校验：引用合法性、数字出处、HITL 判定。
 */
class GroundingGuardServiceTest {

    private final GroundingGuardService guard = new GroundingGuardService();

    private static final List<String> CONTEXT = List.of(
            "【来源：额度说明.pdf › 1.1 额度】现金贷最高额度 50000 元，期限最长 36 期。",
            "【来源：利率说明.pdf › 1.2 利率】年化利率 3.6% 起，按日计息。");

    @Test
    @DisplayName("引用规范且数字均有出处 → 通过")
    void shouldPassWhenGrounded() {
        String answer = "现金贷最高额度 50000 元[1]，年化利率 3.6% 起[2]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, true));

        assertThat(verdict.grounded()).isTrue();
        assertThat(verdict.score()).isEqualTo(1.0);
        assertThat(verdict.citedIndexes()).containsExactly(1, 2);
        assertThat(verdict.hitlRequired()).isFalse();
    }

    @Test
    @DisplayName("未标注任何引用 → NO_CITATION 且转人工")
    void shouldFlagMissingCitation() {
        String answer = "最高额度 50000 元。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, true));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.violations()).contains("NO_CITATION");
        assertThat(verdict.hitlRequired()).isTrue();
        assertThat(verdict.summary()).contains("未标注引用");
    }

    @Test
    @DisplayName("引用编号越界 → 视为编造来源")
    void shouldFlagOutOfRangeCitation() {
        String answer = "额度 50000 元[1]，另外还有隐藏条款[7]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, true));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.violations().stream().anyMatch(v -> v.startsWith("CITATION_OUT_OF_RANGE"))).isTrue();
        assertThat(verdict.score()).isLessThan(0.8);
    }

    @Test
    @DisplayName("回答里的数字在上下文中找不到 → 判为疑似幻觉")
    void shouldFlagUnsupportedNumber() {
        String answer = "现金贷最高额度 80000 元[1]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, true));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.violations()).contains("UNSUPPORTED_NUMBER");
        assertThat(verdict.unsupportedNumbers()).containsExactly("80000");
        assertThat(verdict.hitlRequired()).isTrue();
    }

    @Test
    @DisplayName("千分位与普通写法等价，不误判")
    void shouldTreatThousandSeparatorAsEqual() {
        String answer = "最高额度 50,000 元[1]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, true));

        assertThat(verdict.grounded()).isTrue();
    }

    @Test
    @DisplayName("单个数字字符不参与校验，避免序号噪声")
    void shouldIgnoreSingleDigitNumbers() {
        String answer = "1. 额度 50000 元[1]；2. 利率 3.6%[2]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, true));

        assertThat(verdict.unsupportedNumbers()).isEmpty();
        assertThat(verdict.grounded()).isTrue();
    }

    @Test
    @DisplayName("中文数词不做校验（需语义模型，硬做会大量误判）")
    void shouldNotCheckChineseNumerals() {
        String answer = "利率三点六起[2]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, true));

        assertThat(verdict.unsupportedNumbers()).isEmpty();
    }

    @Test
    @DisplayName("租户关闭引用强制时，无引用不算问题")
    void shouldRespectCitationSwitch() {
        String answer = "最高额度 50000 元。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(false, true));

        assertThat(verdict.grounded()).isTrue();
    }

    @Test
    @DisplayName("关闭 HITL 时仍给出未通过结论，但不建议转人工")
    void shouldRespectHitlSwitch() {
        String answer = "最高额度 80000 元[1]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, CONTEXT, settings(true, false));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.hitlRequired()).isFalse();
    }

    @Test
    @DisplayName("空回答直接判失败")
    void shouldRejectEmptyAnswer() {
        GroundingGuardService.GroundingVerdict verdict = guard.check("  ", CONTEXT, settings(true, true));

        assertThat(verdict.grounded()).isFalse();
        assertThat(verdict.violations()).contains("EMPTY_ANSWER");
    }

    @Test
    @DisplayName("无上下文时跳过数字校验，只校验引用")
    void shouldSkipNumberCheckWithoutContext() {
        String answer = "最高额度 80000 元[1]。";

        GroundingGuardService.GroundingVerdict verdict = guard.check(answer, List.of(), settings(true, true));

        assertThat(verdict.unsupportedNumbers()).isEmpty();
        // 引用越界仍会被抓出来
        assertThat(verdict.grounded()).isFalse();
    }

    private RagRuntimeSettings settings(boolean citationRequired, boolean hitl) {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .citationRequired(citationRequired)
                .hitlOnLowFaithfulness(hitl)
                .faithfulnessThreshold(0.7)
                .build();
    }
}
