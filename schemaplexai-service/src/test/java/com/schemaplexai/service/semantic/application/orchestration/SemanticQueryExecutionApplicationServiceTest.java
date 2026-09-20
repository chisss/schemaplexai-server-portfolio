package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.service.semantic.application.query.PreparedSemanticQuery;
import com.schemaplexai.service.semantic.domain.model.query.QueryIntent;
import com.schemaplexai.service.semantic.domain.model.query.QueryMetric;
import com.schemaplexai.service.semantic.domain.model.query.QueryOutput;
import com.schemaplexai.service.semantic.domain.model.query.QueryPlan;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import com.schemaplexai.service.semantic.domain.model.query.SemanticQueryInterpretation;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;
import com.schemaplexai.service.semantic.domain.port.SemanticQuerySourcePort;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryExecutionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SemanticQueryExecutionApplicationServiceTest {

    @Test
    void preparesOnlyReadyIntentWithServerSideSourceTypeAndSignature() {
        var interpretationService = mock(SemanticQueryApplicationService.class);
        var planService = mock(SemanticQueryPlanApplicationService.class);
        var executionService = mock(SemanticQueryExecutionService.class);
        SemanticQuerySourcePort sourcePort = mock(SemanticQuerySourcePort.class);
        QueryIntent intent = new QueryIntent(
                "version-1", "source-1",
                java.util.List.of(new QueryMetric("urn:metric", "订单金额", "SUM", "orders", "amount")),
                java.util.List.of(), java.util.List.of(), null, 100,
                new QueryOutput("table", "single metric"));
        QueryPlan plan = mock(QueryPlan.class);
        SignedQueryPlan signed = mock(SignedQueryPlan.class);
        when(interpretationService.interpret("订单金额", "version-1", "source-1", null))
                .thenReturn(SemanticQueryInterpretation.ready("订单金额", intent));
        when(sourcePort.databaseType("source-1")).thenReturn("postgresql");
        when(planService.compile(intent, "postgresql")).thenReturn(plan);
        when(executionService.sign(plan)).thenReturn(signed);

        var service = new SemanticQueryExecutionApplicationService(
                interpretationService, planService, executionService, sourcePort);
        PreparedSemanticQuery result = service.prepare("订单金额", "version-1", "source-1");

        assertThat(result.interpretation().status()).isEqualTo(QueryInterpretationStatus.READY);
        assertThat(result.databaseType()).isEqualTo("postgresql");
        assertThat(result.plan()).isSameAs(plan);
        assertThat(result.signedPlan()).isSameAs(signed);
        verify(sourcePort).databaseType("source-1");
        verify(executionService).sign(plan);
    }

    @Test
    void doesNotCompileClarificationResult() {
        var interpretationService = mock(SemanticQueryApplicationService.class);
        var planService = mock(SemanticQueryPlanApplicationService.class);
        var executionService = mock(SemanticQueryExecutionService.class);
        SemanticQuerySourcePort sourcePort = mock(SemanticQuerySourcePort.class);
        when(interpretationService.interpret(any(), any(), any(), any())).thenReturn(
                SemanticQueryInterpretation.clarification(
                        "订单", "version-1", "source-1", java.util.List.of(), java.util.List.of("缺少指标")));

        var service = new SemanticQueryExecutionApplicationService(
                interpretationService, planService, executionService, sourcePort);
        PreparedSemanticQuery result = service.prepare("订单", "version-1", "source-1");

        assertThat(result.ready()).isFalse();
        verify(sourcePort, org.mockito.Mockito.never()).databaseType(any());
        verify(planService, org.mockito.Mockito.never()).compile(any(), any());
    }
}
