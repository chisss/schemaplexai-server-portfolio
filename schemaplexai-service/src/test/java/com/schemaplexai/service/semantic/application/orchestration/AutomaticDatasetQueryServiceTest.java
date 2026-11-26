package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.service.semantic.application.orchestration.AutomaticDatasetQueryService.Candidate;
import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.application.query.SemanticQueryDatasetAccess;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class AutomaticDatasetQueryServiceTest {
    private static final String QUESTION = "按月统计还款金额";
    private final SemanticQueryExecutionApplicationService queries = mock(SemanticQueryExecutionApplicationService.class);
    private final AutomaticDatasetQueryService router = new AutomaticDatasetQueryService(queries);
    private final Candidate ledger = candidate("ledger", "还款流水", true);
    private final Candidate summary = candidate("summary", "还款汇总", true);

    @Test
    void publishedMatchWinsWithoutExploration() {
        miss(ledger);
        var published = ready();
        when(queries.preparePublished(QUESTION, null, summary.access())).thenReturn(published);

        var result = router.plan(QUESTION, null, List.of(ledger, summary));

        assertThat(result.candidate()).isEqualTo(summary);
        assertThat(result.prepared()).isSameAs(published);
        verify(queries).preparePublished(QUESTION, null, ledger.access());
        verify(queries).preparePublished(QUESTION, null, summary.access());
        verifyNoMoreInteractions(queries);
    }

    @Test
    void unambiguousAuthorizedExplorationReturnsItsDataset() {
        var interpretation = miss(ledger);
        var disabled = candidate("disabled", "其他业务", false);
        miss(disabled);
        var explored = ready();
        when(queries.prepareExploration(QUESTION, null, ledger.access(), interpretation, ledger.name()))
                .thenReturn(explored);

        var result = router.plan(QUESTION, null, List.of(ledger, disabled));

        assertThat(result.candidate()).isEqualTo(ledger);
        assertThat(result.prepared()).isSameAs(explored);
    }

    @Test
    void competingPublishedPlansAskForBusinessScopeWithoutReturningAPlan() {
        var published = ready();
        when(queries.preparePublished(QUESTION, null, ledger.access())).thenReturn(published);
        when(queries.preparePublished(QUESTION, null, summary.access())).thenReturn(published);

        var result = router.plan(QUESTION, null, List.of(ledger, summary));

        assertThat(result.candidate()).isNull();
        assertThat(result.prepared().interpretation().status()).isEqualTo(QueryInterpretationStatus.CLARIFICATION);
        assertThat(result.prepared().ready()).isFalse();
    }

    @Test
    void noAuthorizationDoesNotConsultTheQueryEngine() {
        var result = router.plan(QUESTION, null, List.of());
        assertThat(result.prepared().interpretation().status()).isEqualTo(QueryInterpretationStatus.NO_MATCH);
        verifyNoInteractions(queries);
    }

    @Test
    void unmatchedQuestionReportsExplorationProgressAndPreservesCandidateRejection() {
        String question = "按月统计应还但未还的金额";
        var interpretation = SemanticQueryInterpretation.noMatch(question, null, ledger.access().sourceId());
        when(queries.preparePublished(question, null, ledger.access()))
                .thenReturn(new PreparedSemanticQuery(interpretation, null, null, null));
        var rejected = SemanticQueryInterpretation.rejected(question, null, ledger.access().sourceId(),
                List.of("授权字段无法表达应还未还条件"));
        when(queries.prepareExploration(question, null, ledger.access(), interpretation, ledger.name()))
                .thenReturn(new PreparedSemanticQuery(rejected, null, null, null));
        var progress = new ArrayList<String>();

        var result = router.plan(question, null, List.of(ledger), progress::add);

        assertThat(progress).anyMatch(message -> message.contains("正在尝试 NL2SQL"));
        assertThat(result.prepared().interpretation().reasons()).contains("授权字段无法表达应还未还条件");
        assertThat(result.prepared().ready()).isFalse();
        verify(queries).prepareExploration(question, null, ledger.access(), interpretation, ledger.name());
    }

    @Test
    void unmatchedQuestionWithoutExplorationPermissionExplainsWhyItWasNotTried() {
        var disabled = candidate("disabled", "还款业务", false);
        miss(disabled);
        var progress = new ArrayList<String>();

        var result = router.plan(QUESTION, null, List.of(disabled), progress::add);

        assertThat(progress).isEmpty();
        assertThat(result.prepared().interpretation().reasons()).anyMatch(reason -> reason.contains("没有开放 NL2SQL"));
        verify(queries).preparePublished(QUESTION, null, disabled.access());
        verifyNoMoreInteractions(queries);
    }

    private SemanticQueryInterpretation miss(Candidate candidate) {
        var result = SemanticQueryInterpretation.noMatch(QUESTION, null, candidate.access().sourceId());
        when(queries.preparePublished(QUESTION, null, candidate.access()))
                .thenReturn(new PreparedSemanticQuery(result, null, null, null));
        return result;
    }

    private PreparedSemanticQuery ready() {
        var result = mock(PreparedSemanticQuery.class);
        when(result.ready()).thenReturn(true);
        return result;
    }

    private Candidate candidate(String id, String name, boolean explore) {
        return new Candidate(name, new SemanticQueryDatasetAccess(id, 1, null, "test", "loan.rows",
                List.of("amount"), "snapshot", explore));
    }
}
