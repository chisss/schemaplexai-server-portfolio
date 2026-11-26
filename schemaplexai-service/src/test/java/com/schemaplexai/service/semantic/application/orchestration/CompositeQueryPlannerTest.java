package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.service.semantic.domain.port.CompositeQueryModelPort.Intent;
import com.schemaplexai.service.semantic.domain.port.CompositeQueryModelPort.Part;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompositeQueryPlannerTest {
    @Test
    void acceptsGenericIndependentMeasures() {
        var planner = new CompositeQueryPlanner((tenant, question, context) -> new Intent("ALIGN_MONTH", List.of(
                new Part("按月统计新客数", "新客数"), new Part("按月统计续费收入", "续费收入"))));
        var plan = planner.plan("按月对比新客数和续费收入", null);
        assertThat(plan.parts()).hasSize(2);
        assertThat(plan.presentation()).isEqualTo(CompositeQueryPlanner.Presentation.ALIGN_MONTH);
    }

    @Test
    void rejectsSplitThatDropsSharedTimeConstraint() {
        String original = "按月查询最近30天的新客数和续费收入";
        var planner = new CompositeQueryPlanner((tenant, question, context) -> new Intent("ALIGN_MONTH", List.of(
                new Part("按月查询最近30天的新客数", "新客数"), new Part("按月查询续费收入", "续费收入"))));
        assertThat(planner.plan(original, null).parts())
                .containsExactly(new CompositeQueryPlanner.Part(original, original));
    }
}
