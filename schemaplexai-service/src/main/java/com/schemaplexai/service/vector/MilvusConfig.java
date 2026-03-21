package com.schemaplexai.service.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库客户端配置
 *
 * <p>激活条件: {@code milvus.enabled=true}（默认激活，可通过配置关闭）
 * <p>若 Milvus 服务不可用，{@link MilvusVectorService} 内部会捕获异常并降级处理，
 * 不影响系统正常运行。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "milvus", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MilvusConfig {

    @Value("${milvus.host:localhost}")
    private String host;

    @Value("${milvus.port:19530}")
    private int port;

    @Bean(destroyMethod = "close")
    public MilvusClientV2 milvusClient() {
        String uri = String.format("http://%s:%d", host, port);
        log.info("初始化 Milvus 客户端: uri={}", uri);
        ConnectConfig config = ConnectConfig.builder()
                .uri(uri)
                .build();
        return new MilvusClientV2(config);
    }
}
