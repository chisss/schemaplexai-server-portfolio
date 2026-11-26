package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文过滤链：实体交集、文件数量上限、Token 硬预算。
 */
class ContextFilterChainTest {

    private final ContextFilterChain chain = new ContextFilterChain(new EntityMatcher());

    @Test
    @DisplayName("实体无交集的片段被剔除，避免答非所问")
    void shouldDropCandidatesWithoutEntityOverlap() {
        List<RetrievalCandidate> ranked = List.of(
                candidate("c1", "额度合同.pdf", "现金贷产品最高额度五万元，期限十二期"),
                candidate("c2", "考勤制度.pdf", "员工每日打卡时间为上午九点"));

        List<RetrievalCandidate> kept = chain.apply(ranked, query("现金贷额度是多少"), settings()).kept();

        assertThat(kept).extracting(RetrievalCandidate::chunkId).containsExactly("c1");
    }

    @Test
    @DisplayName("最多允许配置数量的不同文件进入上下文")
    void shouldLimitDistinctFiles() {
        List<RetrievalCandidate> ranked = List.of(
                candidate("c1", "A.pdf", "现金贷额度五万元"),
                candidate("c2", "A.pdf", "现金贷期限十二期"),
                candidate("c3", "B.pdf", "现金贷利率三点六"),
                candidate("c4", "C.pdf", "现金贷罚息一点五倍"),
                candidate("c5", "C.pdf", "现金贷提前还款"));

        ContextFilterChain.FilterOutcome outcome = chain.apply(ranked, query("现金贷额度"), settings());

        assertThat(outcome.kept()).extracting(RetrievalCandidate::fileName).containsOnly("A.pdf", "B.pdf");
        assertThat(outcome.droppedByFileLimit()).isEqualTo(2);
    }

    @Test
    @DisplayName("Token 硬上限：超出预算即停止累加，不无限塞片段")
    void shouldStopAtTokenBudget() {
        String longText = "现金贷".repeat(200); // 约 600 token
        List<RetrievalCandidate> ranked = List.of(
                candidate("c1", "A.pdf", longText),
                candidate("c2", "A.pdf", longText),
                candidate("c3", "A.pdf", longText));

        ContextFilterChain.FilterOutcome outcome = chain.apply(ranked, query("现金贷"), settingsWithBudget(800));

        assertThat(outcome.kept()).hasSize(1);
        assertThat(outcome.totalTokens()).isLessThanOrEqualTo(800);
        assertThat(outcome.droppedByTokenBudget()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("单条就超预算时仍保留一条，避免空上下文")
    void shouldKeepAtLeastOneChunk() {
        String huge = "现金贷".repeat(1000);
        List<RetrievalCandidate> ranked = List.of(candidate("c1", "A.pdf", huge));

        ContextFilterChain.FilterOutcome outcome = chain.apply(ranked, query("现金贷"), settingsWithBudget(100));

        assertThat(outcome.kept()).hasSize(1);
    }

    @Test
    @DisplayName("关闭实体过滤后不再剔除，只受文件数与预算约束")
    void shouldSkipEntityFilterWhenDisabled() {
        List<RetrievalCandidate> ranked = List.of(
                candidate("c1", "A.pdf", "现金贷额度五万元"),
                candidate("c2", "A.pdf", "员工每日打卡时间"));

        List<RetrievalCandidate> kept = chain.apply(ranked, query("现金贷额度"),
                settingsBuilder().entityOverlapFilterEnabled(false).build()).kept();

        assertThat(kept).hasSize(2);
    }

    @Test
    @DisplayName("query 抽不出实体时不过滤（宁可多给，不可漏证据）")
    void shouldNotFilterWhenQueryHasNoTokens() {
        List<RetrievalCandidate> ranked = List.of(candidate("c1", "A.pdf", "任意内容"));

        List<RetrievalCandidate> kept = chain.apply(ranked, query("？？"), settings()).kept();

        assertThat(kept).hasSize(1);
    }

    private RetrievalCandidate candidate(String chunkId, String fileName, String text) {
        return new RetrievalCandidate(chunkId, "doc-" + chunkId, fileName, "正文", "document", text,
                null, null, null, null, 0.03, null, "vector");
    }

    private RetrievalQuery query(String text) {
        return new RetrievalQuery("tenant-1", List.of("proj-A"), text, null, null, false);
    }

    private RagRuntimeSettings settings() {
        return settingsBuilder().build();
    }

    private RagRuntimeSettings settingsWithBudget(int budget) {
        return settingsBuilder().contextTokenBudget(budget).build();
    }

    private RagRuntimeSettings.RagRuntimeSettingsBuilder settingsBuilder() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .entityOverlapFilterEnabled(true)
                .maxFilesPerContext(2)
                .contextTokenBudget(4000);
    }
}
