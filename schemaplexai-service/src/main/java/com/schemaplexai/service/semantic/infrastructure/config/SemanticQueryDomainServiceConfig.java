package com.schemaplexai.service.semantic.infrastructure.config;

import com.schemaplexai.service.semantic.domain.service.QueryPlanCompilationService;
import com.schemaplexai.service.database.DatabaseSourceService;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryAuditPort;
import com.schemaplexai.service.semantic.domain.port.SemanticQueryExecutorPort;
import com.schemaplexai.service.semantic.domain.port.SemanticQuerySourcePort;
import com.schemaplexai.service.semantic.domain.service.QueryPlanPolicy;
import com.schemaplexai.service.semantic.domain.service.QueryPlanSignatureService;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryExecutionService;
import com.schemaplexai.service.semantic.infrastructure.database.DatabaseSemanticQueryExecutorAdapter;
import com.schemaplexai.service.semantic.infrastructure.database.DatabaseSemanticQuerySourceAdapter;
import com.schemaplexai.service.semantic.infrastructure.database.MongoAggregationSemanticQueryExecutorAdapter;
import com.schemaplexai.service.semantic.infrastructure.database.MybatisSemanticQueryAuditAdapter;
import com.schemaplexai.service.semantic.infrastructure.database.RoutingSemanticQueryExecutorAdapter;
import com.schemaplexai.dao.mapper.AuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import com.schemaplexai.service.semantic.domain.service.SemanticQueryInterpretationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 将无框架领域服务以 Bean 暴露给 application 编排层。 */
@Configuration
public class SemanticQueryDomainServiceConfig {

    @Bean
    public SemanticQueryInterpretationService semanticQueryInterpretationService() {
        return new SemanticQueryInterpretationService();
    }

    @Bean
    public QueryPlanCompilationService queryPlanCompilationService() {
        return new QueryPlanCompilationService();
    }

    @Bean
    public QueryPlanSignatureService queryPlanSignatureService(
            @Value("${schemaplexai.semantic.query-signing-secret:${jwt.secret}}") String secret) {
        return new QueryPlanSignatureService(secret);
    }

    @Bean
    public QueryPlanPolicy queryPlanPolicy() {
        return new QueryPlanPolicy();
    }

    @Bean
    public SemanticQueryExecutorPort semanticQueryExecutorPort(
            DatabaseSourceService databaseSourceService,
            MongoAggregationSemanticQueryExecutorAdapter mongoAdapter) {
        return new RoutingSemanticQueryExecutorAdapter(
                new DatabaseSemanticQueryExecutorAdapter(databaseSourceService), mongoAdapter);
    }

    @Bean
    public SemanticQuerySourcePort semanticQuerySourcePort(DatabaseSourceService databaseSourceService) {
        return new DatabaseSemanticQuerySourceAdapter(databaseSourceService);
    }

    @Bean
    public SemanticQueryAuditPort semanticQueryAuditPort(
            AuditLogMapper auditLogMapper,
            ObjectMapper objectMapper) {
        return new MybatisSemanticQueryAuditAdapter(auditLogMapper, objectMapper);
    }

    @Bean
    public SemanticQueryExecutionService semanticQueryExecutionService(
            QueryPlanSignatureService signatureService,
            QueryPlanPolicy policy,
            SemanticQueryExecutorPort executor,
            SemanticQueryAuditPort audit) {
        return new SemanticQueryExecutionService(signatureService, policy, executor, audit);
    }
}
