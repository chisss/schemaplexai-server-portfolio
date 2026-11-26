package com.schemaplexai.service.rag.retrieve;

import com.schemaplexai.service.rag.index.Bm25Hit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RRF 融合：手算排名可验证、两路去重、双路命中优先。
 */
class RrfFusionTest {

    private final RrfFusion fusion = new RrfFusion();

    @Test
    @DisplayName("双路都命中排第一，单路命中排在后面（手算校验）")
    void shouldRankBothListHitFirst() {
        List<Bm25Hit> bm25 = List.of(hit("c1", 1), hit("c2", 2));
        List<RrfFusion.VectorHit> vector = List.of(vhit("c1", 0.9), vhit("c3", 0.8));

        List<RetrievalCandidate> fused = fusion.fuse(bm25, vector, 60, 10);

        assertThat(fused.get(0).chunkId()).isEqualTo("c1");
        // c1: 1/61 + 1/61
        assertThat(fused.get(0).rrfScore()).isCloseTo(2.0 / 61, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(fused.get(0).matchedBy()).isEqualTo("bm25+vector");
    }

    @Test
    @DisplayName("同分时保持稳定顺序，不因 Map 迭代抖动")
    void shouldBeDeterministic() {
        List<Bm25Hit> bm25 = List.of(hit("c1", 1), hit("c2", 2));
        List<RrfFusion.VectorHit> vector = List.of(vhit("c3", 0.9));

        List<RetrievalCandidate> first = fusion.fuse(bm25, vector, 60, 10);
        List<RetrievalCandidate> second = fusion.fuse(bm25, vector, 60, 10);

        assertThat(first.stream().map(RetrievalCandidate::chunkId).toList())
                .isEqualTo(second.stream().map(RetrievalCandidate::chunkId).toList());
    }

    @Test
    @DisplayName("k 越小排名差异越被放大")
    void shouldAmplifyRankDifferenceWithSmallerK() {
        List<Bm25Hit> bm25 = List.of(hit("c1", 1), hit("c2", 2));
        List<RrfFusion.VectorHit> vector = List.of();

        List<RetrievalCandidate> smallK = fusion.fuse(bm25, vector, 1, 10);
        List<RetrievalCandidate> largeK = fusion.fuse(bm25, vector, 100, 10);

        double smallGap = smallK.get(0).rrfScore() - smallK.get(1).rrfScore();
        double largeGap = largeK.get(0).rrfScore() - largeK.get(1).rrfScore();
        assertThat(smallGap).isGreaterThan(largeGap);
    }

    @Test
    @DisplayName("limit 截断融合池")
    void shouldTruncateToLimit() {
        List<Bm25Hit> bm25 = List.of(hit("c1", 1), hit("c2", 2), hit("c3", 3));

        assertThat(fusion.fuse(bm25, List.of(), 60, 2)).hasSize(2);
    }

    @Test
    @DisplayName("两路共识弱时判定分布扁平，触发精排")
    void shouldDetectFlatDistribution() {
        // 5 条都只在单路命中且排名接近 → RRF 分差极小
        List<Bm25Hit> bm25 = List.of(hit("c1", 1), hit("c2", 2), hit("c3", 3), hit("c4", 4), hit("c5", 5));

        List<RetrievalCandidate> fused = fusion.fuse(bm25, List.of(), 60, 10);

        assertThat(fusion.isFlatDistribution(fused, 0.15)).isTrue();
    }

    @Test
    @DisplayName("有明显断层时不判扁平")
    void shouldNotDetectFlatWhenGapIsLarge() {
        List<Bm25Hit> bm25 = List.of(hit("c1", 1));
        List<RrfFusion.VectorHit> vector = List.of(vhit("c1", 0.9), vhit("c2", 0.5), vhit("c3", 0.4),
                vhit("c4", 0.3), vhit("c5", 0.2));

        List<RetrievalCandidate> fused = fusion.fuse(bm25, vector, 60, 10);

        assertThat(fusion.isFlatDistribution(fused, 0.15)).isFalse();
    }

    @Test
    @DisplayName("空输入返回空列表，不抛异常")
    void shouldHandleEmptyInput() {
        assertThat(fusion.fuse(List.of(), List.of(), 60, 10)).isEmpty();
        assertThat(fusion.fuse(null, null, 60, 10)).isEmpty();
    }

    private Bm25Hit hit(String chunkId, int rank) {
        return new Bm25Hit(chunkId, "doc-" + chunkId, "file.pdf", "正文", "document",
                "文本" + chunkId, 10.0f - rank, rank);
    }

    private RrfFusion.VectorHit vhit(String chunkId, double score) {
        return new RrfFusion.VectorHit(chunkId, "doc-" + chunkId, "file.pdf", "正文", "document",
                "文本" + chunkId, score);
    }
}
