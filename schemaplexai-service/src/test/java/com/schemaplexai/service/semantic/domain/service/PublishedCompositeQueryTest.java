package com.schemaplexai.service.semantic.domain.service;

import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile.Measure;
import com.schemaplexai.service.semantic.domain.model.ontology.PublishedQueryProfile.Time;
import com.schemaplexai.service.semantic.domain.model.query.QueryInterpretationStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PublishedCompositeQueryTest {
    private final PublishedQueryInterpretationService interpreter = new PublishedQueryInterpretationService(Clock.systemUTC());
    private final PublishedQueryProfile profile = new PublishedQueryProfile("source", "snapshot", "fingerprint",
            "mysql", "crm.accounts", "UTC", List.of(
                    new Measure("n", "urn:n", "NewAccounts", "新客数", "new_accounts", "integer", "int", "sum"),
                    new Measure("r", "urn:r", "RenewalRevenue", "续费收入", "renewal_revenue", "decimal", "decimal(18,2)", "sum")),
            List.of(), List.of(), new Time("t", "urn:t", "BusinessDate", "业务日期", "business_date", "date", "date",
                    List.of("month")));

    @Test
    void plansTwoPublishedMeasuresWithOneMonthlyGrouping() {
        var result = interpreter.interpret("按月查询新客数和续费收入", "v1", profile, "scope");
        assertThat(result.status()).isEqualTo(QueryInterpretationStatus.READY);
        assertThat(result.intent().metrics()).hasSize(2);
        assertThat(result.intent().dimensions()).singleElement().satisfies(d -> assertThat(d.timeGrain()).isEqualTo("month"));
    }

    @Test
    void willNotExecuteOnlyTheKnownMetricWhenSecondMetricIsUnknown() {
        var result = interpreter.interpret("按月查询新客数和退款收入", "v1", profile, "scope");
        assertThat(result.status()).isEqualTo(QueryInterpretationStatus.CLARIFICATION);
    }
}
