package com.schemaplexai.service.semantic.infrastructure.config;

import com.schemaplexai.service.semantic.domain.service.QueryPlanCompilationService;
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
}
