package com.schemaplexai.service.clickhouse;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ClickHouse 分析库连接配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "clickhouse")
public class ClickHouseProperties {

    /** 是否启用 ClickHouse 分析库 */
    private boolean enabled = true;

    /** JDBC 连接地址 */
    private String url;

    /** 用户名 */
    private String username = "default";

    /** 密码 */
    private String password = "clickhouse123";

    /** 每次增量同步最大记录数 */
    private int syncBatchSize = 5000;

    /** 同步安全延迟秒数，避免刚完成事务尚未稳定 */
    private int syncLagSeconds = 10;
}
