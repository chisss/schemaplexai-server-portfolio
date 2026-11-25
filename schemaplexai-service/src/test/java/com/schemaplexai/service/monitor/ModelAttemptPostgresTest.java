package com.schemaplexai.service.monitor;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.schemaplexai.dao.mapper.AgentMapper;
import com.schemaplexai.dao.mapper.AgentTraceSpanMapper;
import com.schemaplexai.model.vo.monitor.AgentTraceSpanVO;
import com.schemaplexai.model.vo.monitor.ModelAttemptMetadata;
import com.schemaplexai.service.monitor.impl.AgentTraceServiceImpl;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class ModelAttemptPostgresTest {

    private static final String TENANT = "40000000-0000-0000-0000-000000000001";

    @Test
    void shouldPersistAnAttemptOnceWithReportedMetadataAndReadHistoricalTrace() throws Exception {
        String url = System.getenv("MODEL_ATTEMPT_DB_TEST_URL");
        assumeTrue(url != null && !url.isBlank(), "在隔离的 PostgreSQL schema 运行");
        var dataSource = new DriverManagerDataSource(url, "schemaplexai",
                System.getenv().getOrDefault("DB_PASSWORD", "schemaplexai123"));
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE sf_agent_trace_span (
                    id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid, created_by uuid,
                    created_at timestamp DEFAULT CURRENT_TIMESTAMP, updated_by uuid,
                    updated_at timestamp DEFAULT CURRENT_TIMESTAMP, deleted integer DEFAULT 0,
                    trace_id varchar(255), span_id varchar(255), parent_span_id varchar(255), agent_id varchar(255),
                    span_type varchar(255), name varchar(255), started_at timestamp, duration_ms bigint,
                    input_tokens bigint, output_tokens bigint, cost numeric, status varchar(255),
                    failure_category varchar(255), failure_reason varchar(255), recoverable boolean
                )
                """);
        try {
            verifyMigrationAndAttempts(dataSource, jdbc);
        } finally {
            jdbc.execute("DROP TABLE sf_agent_trace_span");
        }
    }

    private void verifyMigrationAndAttempts(DriverManagerDataSource dataSource, JdbcTemplate jdbc) throws Exception {
        jdbc.update("""
                INSERT INTO sf_agent_trace_span (tenant_id, trace_id, span_id, span_type, status, input_tokens, output_tokens)
                VALUES (?::uuid, 'run', 'old-span', 'LLM', 'SUCCESS', 7, 2)
                """, TENANT);
        var migration = new ResourceDatabasePopulator(new ClassPathResource("db/migration/agent-model-attempt-metadata.sql"));
        migration.execute(dataSource);
        migration.execute(dataSource);

        var service = new AgentTraceServiceImpl(mapper(dataSource), mock(AgentMapper.class), new TraceFailureClassifier());
        var startedAt = LocalDateTime.of(2026, 11, 22, 12, 0);
        var first = new ModelAttemptMetadata(4, "inference", 1, "provider-id", false, false, 9, 0, 3);
        var second = new ModelAttemptMetadata(4, "inference", 2, null, true, true, null, null, null);
        String firstId = UUID.randomUUID().toString();
        String secondId = UUID.randomUUID().toString();
        service.recordModelAttempt("run", TENANT, "agent", firstId, "provider:model", startedAt, 82,
                19, 6, null, "SUCCESS", first);
        service.recordModelAttempt("run", TENANT, "agent", firstId, "provider:model", startedAt, 82,
                19, 6, null, "SUCCESS", first);
        service.recordModelAttempt("run", TENANT, "agent", secondId, "provider:model", startedAt.plusSeconds(1), 15,
                11, 0, null, "FAILED", second);
        service.recordModelSpan("run", TENANT, "agent", "provider:model", startedAt.plusSeconds(2), 20,
                5, 3, BigDecimal.ONE, "SUCCESS");

        List<AgentTraceSpanVO> spans = service.listTraceSpans("run");
        assertThat(spans).hasSize(4);
        assertThat(spans).filteredOn(span -> firstId.equals(span.getSpanId())).singleElement().satisfies(span -> {
            assertThat(span.getModelAttempt()).isEqualTo(first);
            assertThat(span.getStartedAt()).isEqualTo(startedAt);
            assertThat(span.getCost()).isNull();
        });
        assertThat(spans).filteredOn(span -> secondId.equals(span.getSpanId())).singleElement().satisfies(span -> {
            assertThat(span.getModelAttempt()).isEqualTo(second);
            assertThat(span.getStatus()).isEqualTo("FAILED");
            assertThat(span.getFailureCategory()).isNotBlank();
        });
        assertThat(spans.stream().filter(span -> span.getModelAttempt() != null).mapToLong(AgentTraceSpanVO::getInputTokens).sum())
                .isEqualTo(30);
        assertThat(spans.stream().filter(span -> span.getModelAttempt() != null).mapToLong(AgentTraceSpanVO::getOutputTokens).sum())
                .isEqualTo(6);
        assertThat(spans).filteredOn(span -> span.getModelAttempt() == null).hasSize(2);
    }

    private AgentTraceSpanMapper mapper(DriverManagerDataSource dataSource) throws Exception {
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTraceSpanMapper.class);
        var interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                return new StringValue(TENANT);
            }
        }));
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setPlugins(interceptor);
        return new SqlSessionTemplate(factory.getObject()).getMapper(AgentTraceSpanMapper.class);
    }
}
