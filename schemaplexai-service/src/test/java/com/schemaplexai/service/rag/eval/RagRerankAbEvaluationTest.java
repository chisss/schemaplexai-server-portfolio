package com.schemaplexai.service.rag.eval;

import com.schemaplexai.model.dto.system.RagRetrievalEvalRequest;
import com.schemaplexai.model.vo.system.RagRerankAbVO;
import com.schemaplexai.model.vo.system.RagRetrievalEvalVO;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.retrieve.RetrievalCandidate;
import com.schemaplexai.service.rag.retrieve.RetrievalOrchestrator;
import com.schemaplexai.service.rag.retrieve.RetrievalOutcome;
import com.schemaplexai.service.rag.retrieve.RetrievalTrace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 精排 A/B：设置覆盖是否真的生效、增量计算是否正确、精排未生效时是否如实暴露。
 */
class RagRerankAbEvaluationTest {

    @Test
    @DisplayName("A/B 用设置覆盖跑两组，且精排组质量更高")
    void shouldCompareWithAndWithoutRerank() {
        RagConfigService configService = mock(RagConfigService.class);
        when(configService.resolveSettings(anyString())).thenReturn(baseSettings());
        RetrievalOrchestrator orchestrator = mock(RetrievalOrchestrator.class);
        List<RagRuntimeSettings> seen = new ArrayList<>();

        when(orchestrator.retrieve(any(), anyBoolean(), any())).thenAnswer(invocation -> {
            RagRuntimeSettings settings = invocation.getArgument(2);
            seen.add(settings);
            boolean rerank = settings != null && settings.isRerankActive();
            // 精排组把正确答案排到第一，未精排组排到第二
            return outcome(rerank, settings != null && settings.isRerankActive());
        });

        RagRetrievalEvaluationService service = new RagRetrievalEvaluationService(configService, orchestrator);
        RagRerankAbVO report = service.evaluateRerankAb("tenant-1", request());

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).isRerankActive()).isFalse();
        assertThat(seen.get(1).isRerankActive()).isTrue();
        // 两组都必须关闭缓存，避免互相命中
        assertThat(seen).allSatisfy(settings -> assertThat(settings.isRetrievalCacheEnabled()).isFalse());

        assertThat(report.getWithRerank().getMeanRecallAtK())
                .isGreaterThan(report.getRrfOnly().getMeanRecallAtK());
        assertThat(report.getWithRerank().getMeanMrr())
                .isGreaterThan(report.getRrfOnly().getMeanMrr());
        assertThat(report.getDelta().getRecallAtK()).isPositive();
        assertThat(report.getRerankEffectiveCases()).isEqualTo(1);
    }

    @Test
    @DisplayName("精排未真正执行时，rerankEffectiveCases=0，提示结论不可信")
    void shouldExposeWhenRerankNeverApplied() {
        RagConfigService configService = mock(RagConfigService.class);
        when(configService.resolveSettings(anyString())).thenReturn(baseSettings());
        RetrievalOrchestrator orchestrator = mock(RetrievalOrchestrator.class);
        // 精排模型不可用：trace.rerankApplied=false
        when(orchestrator.retrieve(any(), anyBoolean(), any()))
                .thenReturn(outcome(false, false));

        RagRetrievalEvaluationService service = new RagRetrievalEvaluationService(configService, orchestrator);
        RagRerankAbVO report = service.evaluateRerankAb("tenant-1", request());

        assertThat(report.getRerankEffectiveCases()).isZero();
        assertThat(report.getDelta().getRecallAtK()).isZero();
    }

    @Test
    @DisplayName("单次评测报告带平均延迟，供成本对比")
    void shouldReportAverageLatency() {
        RagConfigService configService = mock(RagConfigService.class);
        when(configService.resolveSettings(anyString())).thenReturn(baseSettings());
        RetrievalOrchestrator orchestrator = mock(RetrievalOrchestrator.class);
        when(orchestrator.retrieve(any(), anyBoolean(), any())).thenReturn(outcome(true, true));

        RagRetrievalEvaluationService service = new RagRetrievalEvaluationService(configService, orchestrator);
        RagRetrievalEvalVO report = service.evaluate("tenant-1", request());

        assertThat(report.getAvgLatencyMs()).isNotNull();
        assertThat(report.getCases()).hasSize(1);
        assertThat(report.getCases().get(0).getRerankApplied()).isTrue();
    }

    /** 精排命中时正确答案排第一；未精排时正确答案根本没进候选 */
    private RetrievalOutcome outcome(boolean hitFirst, boolean rerankApplied) {
        List<RetrievalCandidate> chunks = hitFirst
                ? List.of(
                        candidate("c-doc-1", "doc-1", "现金贷额度 50000 元", 1, rerankApplied ? 0.9 : null),
                        candidate("c-doc-9", "doc-9", "无关内容", 2, null))
                : List.of(
                        candidate("c-doc-9", "doc-9", "无关内容", 1, null),
                        candidate("c-doc-8", "doc-8", "另一段无关内容", 2, null));
        RetrievalTrace trace = RetrievalTrace.builder()
                .fusedCount(2)
                .keptCount(2)
                .contextTokens(120)
                .rerankApplied(rerankApplied)
                .totalLatencyMs(80)
                .build();
        return new RetrievalOutcome(chunks, "context", trace);
    }

    private RetrievalCandidate candidate(String chunkId, String docId, String text, int bm25Rank, Double rerankScore) {
        return new RetrievalCandidate(chunkId, docId, "a.pdf", "正文", "document",
                "【来源：a.pdf】" + text, bm25Rank, null, null, 0.9, 0.03 - bm25Rank * 0.01,
                rerankScore, "bm25");
    }

    private RagRetrievalEvalRequest request() {
        RagRetrievalEvalRequest request = new RagRetrievalEvalRequest();
        request.setK(5);
        RagRetrievalEvalRequest.EvalCase evalCase = new RagRetrievalEvalRequest.EvalCase();
        evalCase.setQuery("现金贷额度是多少");
        evalCase.setExpectedDocIds(List.of("doc-1"));
        request.setCases(List.of(evalCase));
        return request;
    }

    private RagRuntimeSettings baseSettings() {
        return RagRuntimeSettings.builder()
                .tenantId("tenant-1")
                .enabled(true)
                .retrievalTopK(5)
                .rerankerEnabled(false)
                .rerankPolicy("auto")
                .retrievalCacheEnabled(true)
                .evalRecallK(5)
                .build();
    }
}
