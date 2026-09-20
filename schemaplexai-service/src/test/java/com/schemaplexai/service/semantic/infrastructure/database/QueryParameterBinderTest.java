package com.schemaplexai.service.semantic.infrastructure.database;

import com.schemaplexai.service.semantic.domain.model.query.QueryParameter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueryParameterBinderTest {

    private final QueryParameterBinder binder = new QueryParameterBinder();

    @Test
    void bindsNumbersAndEscapesStringValuesWithoutChangingIdentifiers() {
        String sql = binder.bind(
                "SELECT * FROM orders WHERE amount >= :p1 AND status = :p2",
                List.of(new QueryParameter("p1", "30"), new QueryParameter("p2", "done' OR 1=1")));

        assertThat(sql).isEqualTo(
                "SELECT * FROM orders WHERE amount >= 30 AND status = 'done'' OR 1=1'");
    }

    @Test
    void rejectsUnboundParameter() {
        assertThatThrownBy(() -> binder.bind("SELECT 1 WHERE id = :missing", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbound");
    }
}
