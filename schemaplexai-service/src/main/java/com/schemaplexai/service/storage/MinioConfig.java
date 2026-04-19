package com.schemaplexai.service.storage;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置：初始化 {@link MinioClient} 并幂等创建默认 bucket。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final MinioProperties properties;

    @Bean
    public MinioClient minioClient() {
        log.info("初始化 MinIO 客户端: endpoint={}, bucket={}", properties.getEndpoint(), properties.getBucket());
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /**
     * 启动时检查默认 bucket 是否存在，不存在则创建（幂等）。
     */
    @Bean
    public ApplicationRunner minioBucketInitializer(MinioClient minioClient) {
        return args -> {
            if (!properties.isAutoCreateBucket()) {
                log.info("MinIO auto-create-bucket=false，跳过 bucket 初始化: bucket={}", properties.getBucket());
                return;
            }
            try {
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                        .bucket(properties.getBucket())
                        .build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder()
                            .bucket(properties.getBucket())
                            .build());
                    log.info("MinIO bucket 创建成功: bucket={}", properties.getBucket());
                } else {
                    log.info("MinIO bucket 已存在: bucket={}", properties.getBucket());
                }
            } catch (Exception e) {
                log.warn("MinIO bucket 初始化失败（不阻塞启动）: bucket={}, error={}",
                        properties.getBucket(), e.getMessage());
            }
        };
    }
}
