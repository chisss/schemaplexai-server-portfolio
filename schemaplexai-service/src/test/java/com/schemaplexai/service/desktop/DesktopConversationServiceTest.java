package com.schemaplexai.service.desktop;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.dao.mapper.AgentExecutionMapper;
import com.schemaplexai.model.entity.AgentExecution;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesktopConversationServiceTest {

    @BeforeAll
    static void initializeMapperMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),
                "desktop-turns"), AgentExecution.class);
    }

    @ParameterizedTest
    @CsvSource({
            "desktop-purchase,true",
            "tenant-1:desktop-purchase,true",
            "tenant-1:desktop-purchase,false"
    })
    void readsCompletedTurnAcrossConversationIdFormats(String storedConversation, boolean receiptSaved) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var workspace = mock(DesktopWorkspaceService.class);
        var executions = mock(AgentExecutionMapper.class);
        var service = new DesktopConversationService(jdbc, null, workspace, null, null, null, null, executions, null);
        var record = mock(ResultSet.class);
        when(record.getString("id")).thenReturn("turn-1");
        when(record.getString("state")).thenReturn("running");
        when(record.getString("execution_id")).thenReturn(receiptSaved ? "execution-1" : null);
        when(record.getString("request_id")).thenReturn("request-1");
        when(jdbc.queryForObject(contains("count(*)"), eq(Long.class), eq("desktop-purchase"))).thenReturn(1L);
        when(jdbc.query(contains("ORDER BY sequence"), ArgumentMatchers.<RowMapper<Object>>any(),
                eq("desktop-purchase"), eq(20), eq(0L))).thenAnswer(call -> {
            RowMapper<Object> mapper = call.getArgument(1);
            return List.of(mapper.mapRow(record, 0));
        });

        var execution = new AgentExecution();
        execution.setId("execution-1");
        execution.setConversationId(storedConversation);
        execution.setStatus("completed");
        execution.setOutputResult("办公采购清单已完成");
        var lookups = new AtomicInteger();
        when(executions.selectList(any())).thenAnswer(call -> {
            LambdaQueryWrapper<AgentExecution> query = call.getArgument(0);
            String where = query.getSqlSegment();
            var scoped = query.getParamNameValuePairs();
            assertThat(where).contains("tenant_id =", "created_by =", "conversation_id IN");
            assertThat(scoped).containsValues("tenant-1", "employee-1", "desktop-purchase", "tenant-1:desktop-purchase");
            boolean byId = lookups.getAndIncrement() > 0 || receiptSaved;
            assertThat(where).contains(byId ? "id =" : "input_context->>'desktopRequestId'");
            assertThat(scoped).containsValue(byId ? "execution-1" : "request-1");
            return scoped.containsValue(storedConversation) ? List.of(execution) : List.of();
        });

        SecurityUtil.setCurrentTenantId("tenant-1");
        SecurityUtil.setCurrentUserId("employee-1");
        try {
            var turn = service.messages("desktop-purchase", 1, 20).getRecords().getFirst();
            assertThat(turn.state()).isEqualTo("completed");
            assertThat(turn.output()).isEqualTo("办公采购清单已完成");
            verify(workspace).conversation("desktop-purchase");
        } finally {
            SecurityUtil.clear();
        }
    }
}
