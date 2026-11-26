package com.schemaplexai.service.semantic.infrastructure.database;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.service.semantic.domain.model.query.SignedQueryPlan;
import com.schemaplexai.service.semantic.domain.port.ExploratoryQueryPlanStore;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

/** 在所有应用实例间共享短时探索计划。 */
@Repository
@RequiredArgsConstructor
public class RedisExploratoryQueryPlanStore implements ExploratoryQueryPlanStore {
    private static final Duration TTL = Duration.ofMinutes(10);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    @Override
    public void save(String key, SignedQueryPlan plan) {
        try {
            redis.opsForValue().set("semantic:exploration:" + key, json.writeValueAsString(plan), TTL);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_EXECUTION_UNAVAILABLE, "无法保存探索计划");
        } catch (DataAccessException exception) {
            throw unavailable();
        }
    }

    @Override
    public Optional<SignedQueryPlan> find(String key) {
        String value;
        try {
            value = redis.opsForValue().get("semantic:exploration:" + key);
        } catch (DataAccessException exception) {
            throw unavailable();
        }
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, SignedQueryPlan.class));
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ResultCode.SEMANTIC_QUERY_PLAN_CHANGED, "探索计划需要重新生成");
        }
    }

    private BusinessException unavailable() {
        return new BusinessException(ResultCode.SEMANTIC_QUERY_EXECUTION_UNAVAILABLE, "探索计划服务暂不可用，请稍后重试");
    }
}
