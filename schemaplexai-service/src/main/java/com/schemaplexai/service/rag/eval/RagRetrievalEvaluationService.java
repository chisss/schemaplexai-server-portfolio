package com.schemaplexai.service.rag.eval;

import com.schemaplexai.model.dto.system.RagRetrievalEvalRequest;
import com.schemaplexai.model.vo.system.RagRerankAbVO;
import com.schemaplexai.model.vo.system.RagRetrievalEvalVO;
import com.schemaplexai.service.rag.RagConfigService;
import com.schemaplexai.service.rag.RagDefaults;
import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.retrieve.RetrievalCandidate;
import com.schemaplexai.service.rag.retrieve.RetrievalOrchestrator;
import com.schemaplexai.service.rag.retrieve.RetrievalOutcome;
import com.schemaplexai.service.rag.retrieve.RetrievalQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索层评测执行器。
 *
 * <p>跑一遍真实检索链路（含双路召回、RRF、精排门禁、过滤），对每个 case 计算
 * Recall@K / MRR / 上下文 token / 装配率，最后汇总。
 *
 * <p>为什么要在"真实链路"上跑而不是离线用离线索引：
 * 我们要评测的是<b>这套编排</b>，包括降级与过滤；离线重建索引会得到好看但无用的数字。
 *
 * <p>A/B 场景：同一批 case 分别用 {@code rerankPolicy=never} 与 {@code always} 跑两次，
 * 对比质量提升与延迟/成本增量，就能回答"这个租户到底该不该开精排"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagRetrievalEvaluationService {

    private final RagConfigService ragConfigService;
    private final RetrievalOrchestrator retrievalOrchestrator;

    public RagRetrievalEvalVO evaluate(String tenantId, RagRetrievalEvalRequest request) {
        return evaluateWith(tenantId, ragConfigService.resolveSettings(tenantId), request);
    }

    /**
     * 精排 A/B：同一批 case 分别跑「仅 RRF」与「RRF + Cross-Encoder」，输出质量与成本增量。
     *
     * <p>两组实验都强制关闭检索缓存与改写租户配置（用 settings 覆盖），原因：
     * 缓存键不含精排策略，共用缓存会让第二组直接命中第一组结果，得出"精排没影响"的错误结论。
     */
    public RagRerankAbVO evaluateRerankAb(String tenantId, RagRetrievalEvalRequest request) {
        RagRuntimeSettings base = ragConfigService.resolveSettings(tenantId);
        RagRuntimeSettings rrfOnly = base.toBuilder()
                .rerankerEnabled(false)
                .rerankPolicy(RagDefaults.RERANK_POLICY_NEVER)
                .retrievalCacheEnabled(false)
                .build();
        RagRuntimeSettings withRerank = base.toBuilder()
                .rerankerEnabled(true)
                .rerankPolicy(RagDefaults.RERANK_POLICY_ALWAYS)
                .retrievalCacheEnabled(false)
                .build();

        RagRetrievalEvalVO baseline = evaluateWith(tenantId, rrfOnly, request);
        RagRetrievalEvalVO reranked = evaluateWith(tenantId, withRerank, request);

        RagRerankAbVO vo = new RagRerankAbVO();
        vo.setK(baseline.getK());
        vo.setCaseCount(baseline.getCaseCount());
        vo.setRrfOnly(baseline);
        vo.setWithRerank(reranked);
        vo.setRerankEffectiveCases(countRerankApplied(reranked));

        RagRerankAbVO.Delta delta = new RagRerankAbVO.Delta();
        delta.setRecallAtK(round(reranked.getMeanRecallAtK() - baseline.getMeanRecallAtK()));
        delta.setMrr(round(reranked.getMeanMrr() - baseline.getMeanMrr()));
        delta.setAvgContextTokens(round(reranked.getAvgContextTokens() - baseline.getAvgContextTokens()));
        delta.setAvgLatencyMs(round(reranked.getAvgLatencyMs() - baseline.getAvgLatencyMs()));
        vo.setDelta(delta);
        return vo;
    }

    RagRetrievalEvalVO evaluateWith(String tenantId, RagRuntimeSettings settings, RagRetrievalEvalRequest request) {
        int k = request.getK() != null && request.getK() > 0 ? request.getK() : settings.getEvalRecallK();
        List<String> projectIds = request.getProjectIds() == null ? List.of() : request.getProjectIds();

        List<RetrievalMetrics.CaseResult> results = new ArrayList<>();
        List<RagRetrievalEvalVO.CaseResult> caseVOs = new ArrayList<>();

        for (RagRetrievalEvalRequest.EvalCase evalCase : request.getCases()) {
            if (evalCase == null || !StringUtils.hasText(evalCase.getQuery())) {
                continue;
            }
            long startMs = System.currentTimeMillis();
            RetrievalOutcome outcome = retrievalOrchestrator.retrieve(
                    new RetrievalQuery(tenantId, projectIds, evalCase.getQuery(), k, null, false),
                    false, settings);
            long latencyMs = System.currentTimeMillis() - startMs;

            List<String> retrievedDocIds = distinctDocIds(outcome.chunks());
            double recall = RetrievalMetrics.recallAtK(evalCase.getExpectedDocIds(), retrievedDocIds, k);
            double rr = RetrievalMetrics.reciprocalRank(evalCase.getExpectedDocIds(), retrievedDocIds);
            int contextTokens = outcome.trace().contextTokens();
            int fusedCount = Math.max(1, outcome.trace().fusedCount());
            double keptRatio = outcome.chunks().size() / (double) fusedCount;

            results.add(new RetrievalMetrics.CaseResult(evalCase.getQuery(), recall, rr,
                    outcome.chunks().size(), contextTokens, keptRatio, latencyMs));

            RagRetrievalEvalVO.CaseResult caseVO = new RagRetrievalEvalVO.CaseResult();
            caseVO.setQuery(evalCase.getQuery());
            caseVO.setRecallAtK(recall);
            caseVO.setMrr(rr);
            caseVO.setRetrieved(outcome.chunks().size());
            caseVO.setContextTokens(contextTokens);
            caseVO.setKeptRatio(round(keptRatio));
            caseVO.setLatencyMs(latencyMs);
            caseVO.setRerankApplied(outcome.trace().rerankApplied());
            caseVOs.add(caseVO);
        }

        RetrievalMetrics.Aggregate aggregate = RetrievalMetrics.aggregate(results, k);
        double avgLatency = results.stream().mapToLong(RetrievalMetrics.CaseResult::latencyMs).average().orElse(0);
        log.info("RAG 检索评测完成: tenantId={}, cases={}, recall@{}={}, mrr={}, avgTokens={}, avgLatencyMs={}",
                tenantId, aggregate.caseCount(), k, aggregate.meanRecallAtK(), aggregate.meanMrr(),
                aggregate.avgContextTokens(), Math.round(avgLatency));

        RagRetrievalEvalVO vo = new RagRetrievalEvalVO();
        vo.setK(aggregate.k());
        vo.setCaseCount(aggregate.caseCount());
        vo.setMeanRecallAtK(aggregate.meanRecallAtK());
        vo.setMeanMrr(aggregate.meanMrr());
        vo.setAvgContextTokens(aggregate.avgContextTokens());
        vo.setAvgKeptRatio(aggregate.avgKeptRatio());
        vo.setAvgLatencyMs(round(avgLatency));
        vo.setCases(caseVOs);
        return vo;
    }

    /** A/B 里"精排真的跑过"的 case 数；为 0 说明精排模型不可用，此时 delta 不可信 */
    private int countRerankApplied(RagRetrievalEvalVO report) {
        if (report.getCases() == null) {
            return 0;
        }
        return (int) report.getCases().stream()
                .filter(c -> Boolean.TRUE.equals(c.getRerankApplied()))
                .count();
    }

    private List<String> distinctDocIds(List<RetrievalCandidate> chunks) {
        Set<String> ids = new LinkedHashSet<>();
        if (chunks != null) {
            for (RetrievalCandidate chunk : chunks) {
                if (StringUtils.hasText(chunk.docId())) {
                    ids.add(chunk.docId());
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private double round(double value) {
        return Math.round(value * 10000) / 10000.0D;
    }
}
