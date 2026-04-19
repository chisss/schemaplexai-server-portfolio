package com.schemaplexai.service.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MinIO 对象存储配置属性
 *
 * <p>对应 application-*.yml 中的 {@code minio.*} 节。
 */
@Data
@Component
@ConfigurationProperties(prefix = "minio")
public class MinioProperties {

    /** MinIO 服务地址，例如 http://localhost:9000 */
    private String endpoint;

    /** 访问密钥（Access Key） */
    private String accessKey;

    /** 私密密钥（Secret Key） */
    private String secretKey;

    /** 默认 bucket 名称，知识文档统一落到此 bucket */
    private String bucket = "schemaplexai";

    /** 启动时是否自动创建缺失的 bucket（生产环境可关闭） */
    private boolean autoCreateBucket = true;

    /** 预签名 URL 默认有效期（秒） */
    private int presignExpirySeconds = 300;
}
