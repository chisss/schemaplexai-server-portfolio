package com.schemaplexai.service.semantic.infrastructure.jena;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 语义库运行参数；默认值适合开发环境，生产环境必须显式配置。 */
@Data
@Component
@ConfigurationProperties(prefix = "schemaplexai.semantic.store")
public class SemanticStoreProperties {

    private Path directory = Path.of("./data/semantic-tdb2");
    private boolean singleWriter = true;
    private int maxAssertedTriples = 200_000;
    private int maxInferredTriples = 1_000_000;
    private int maxGraphNodes = 500;
    private long queryTimeoutMillis = 3_000;
}
