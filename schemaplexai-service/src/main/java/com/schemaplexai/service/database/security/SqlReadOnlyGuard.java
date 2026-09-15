package com.schemaplexai.service.database.security;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.DescribeStatement;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowColumnsStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.show.ShowIndexStatement;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SQL 只读语法门禁
 */
@Component
public class SqlReadOnlyGuard {

    private static final int MAX_SQL_LENGTH = 100_000;

    public void validate(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw invalid("SQL不能为空");
        }
        if (sql.length() > MAX_SQL_LENGTH) {
            throw invalid("SQL长度超过安全限制");
        }

        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException exception) {
            throw invalid("SQL语法解析失败");
        }
        if (statements.size() != 1) {
            throw invalid("仅允许执行一条 SQL 语句");
        }
        validateStatement(statements.get(0));
    }

    private void validateStatement(Statement statement) {
        if (statement instanceof Select select) {
            validateSelect(select);
            return;
        }
        if (statement instanceof ExplainStatement explain) {
            validateSelect(explain.getStatement());
            return;
        }
        if (statement instanceof ShowStatement
                || statement instanceof ShowColumnsStatement
                || statement instanceof ShowIndexStatement
                || statement instanceof ShowTablesStatement
                || statement instanceof DescribeStatement) {
            return;
        }
        throw invalid("当前仅允许执行只读查询语句");
    }

    private void validateSelect(Select select) {
        if (select == null) {
            throw invalid("EXPLAIN 只能用于 SELECT 查询");
        }
        if (select.getForMode() != null || select.getForUpdateTable() != null) {
            throw invalid("不允许执行带行锁的 SELECT");
        }
        if (select.getPlainSelect() != null
                && (select.getPlainSelect().getIntoTempTable() != null
                || (select.getPlainSelect().getIntoTables() != null
                && !select.getPlainSelect().getIntoTables().isEmpty()))) {
            throw invalid("不允许执行 SELECT INTO");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ResultCode.BAD_REQUEST, message);
    }
}
