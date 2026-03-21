package com.schemaplexai.web.config;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 自定义ID生成器 - 生成标准36位带连字符UUID
 * <p>
 * MyBatis-Plus 默认的 ASSIGN_UUID 使用 IdWorker.get32UUID()，
 * 生成32位无连字符UUID（如 a1b2c3d4e5f678901234567890abcdef），
 * 但 PostgreSQL 的 UUID 类型存储时会自动转换为36位带连字符格式。
 * 导致 Java 对象中的 id 与数据库实际存储的 id 格式不一致，
 * 后续用 Java 对象中的 id 进行关联查询时匹配不上。
 * <p>
 * 本生成器使用 java.util.UUID.randomUUID()，
 * 生成标准36位带连字符UUID（如 a1b2c3d4-e5f6-7890-1234-567890abcdef），
 * 与 PostgreSQL UUID 类型完全兼容。
 */
@Component
public class StandardUuidGenerator implements IdentifierGenerator {

    @Override
    public Number nextId(Object entity) {
        // 不使用数字ID
        throw new UnsupportedOperationException("本项目使用UUID作为主键，不支持数字ID");
    }

    @Override
    public String nextUUID(Object entity) {
        // 生成标准36位带连字符UUID，与PostgreSQL UUID类型兼容
        return UUID.randomUUID().toString();
    }
}
