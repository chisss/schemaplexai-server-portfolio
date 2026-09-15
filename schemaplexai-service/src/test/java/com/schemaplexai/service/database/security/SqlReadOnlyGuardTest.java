package com.schemaplexai.service.database.security;

import com.schemaplexai.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlReadOnlyGuardTest {

    private final SqlReadOnlyGuard guard = new SqlReadOnlyGuard();

    @Test
    void shouldAllowSelectCteAndMetadataStatements() {
        assertThatCode(() -> guard.validate("SELECT id FROM orders WHERE status = 'completed'"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("WITH recent AS (SELECT id FROM orders) SELECT * FROM recent"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("EXPLAIN SELECT id FROM orders"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("SHOW search_path"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("SHOW TABLES"))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.validate("DESCRIBE orders"))
                .doesNotThrowAnyException();
    }

    @Test
    void shouldRejectMutationAndMultipleStatements() {
        assertThatThrownBy(() -> guard.validate("DELETE FROM orders"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> guard.validate("SELECT 1; DROP TABLE orders"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("仅允许执行一条 SQL 语句");
    }

    @Test
    void shouldRejectSelectIntoAndRowLocks() {
        assertThatThrownBy(() -> guard.validate("SELECT * INTO archived_orders FROM orders"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("不允许执行 SELECT INTO");
        assertThatThrownBy(() -> guard.validate("SELECT * FROM orders FOR UPDATE"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("不允许执行带行锁的 SELECT");
    }

    @Test
    void shouldRejectInvalidSqlInsteadOfFallingBackToKeywordChecks() {
        assertThatThrownBy(() -> guard.validate("SELECT FROM"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("SQL语法解析失败");
    }
}
