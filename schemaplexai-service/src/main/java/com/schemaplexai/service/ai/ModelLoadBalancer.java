package com.schemaplexai.service.ai;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 模型组负载均衡器
 * 分片键仅到tenantId+agentId，模型组内实现负载均衡。
 */
@Slf4j
@Component
public class ModelLoadBalancer {

    @Value("${schemaplexai.ai.model-group.strategy:round_robin}")
    private String strategy;

    private final ConcurrentHashMap<String, AtomicInteger> roundRobinState = new ConcurrentHashMap<>();

    /**
     * 从候选模型列表中选择一个模型（下标）
     *
     * @param groupId    模型组ID
     * @param candidates  候选模型列表
     * @param shardKey   分片键（用于hash策略）
     * @return 选中的模型下标
     */
    public int selectIndex(String groupId, List<?> candidates, String shardKey) {
        if (CollectionUtils.isEmpty(candidates)) {
            return 0;
        }
        int size = candidates.size();
        if ("round_robin".equalsIgnoreCase(strategy)) {
            AtomicInteger counter = roundRobinState.computeIfAbsent(groupId, ignored -> new AtomicInteger(0));
            return Math.floorMod(counter.getAndIncrement(), size);
        }
        if (StringUtils.hasText(shardKey)) {
            return Math.floorMod(shardKey.hashCode(), size);
        }
        AtomicInteger fallback = roundRobinState.computeIfAbsent(groupId + ":fallback", ignored -> new AtomicInteger(0));
        return Math.floorMod(fallback.getAndIncrement(), size);
    }

    /**
     * 选择模型（返回模型ID）
     *
     * @param groupId      模型组ID
     * @param modelIds     候选模型ID列表
     * @param shardKey     分片键
     * @return 选中的模型ID
     */
    public String selectModelId(String groupId, List<String> modelIds, String shardKey) {
        if (CollectionUtils.isEmpty(modelIds)) {
            throw new BusinessException(ResultCode.MODEL_GROUP_ALL_UNAVAILABLE, "模型组为空");
        }
        int index = selectIndex(groupId, modelIds, shardKey);
        return modelIds.get(index);
    }
}
