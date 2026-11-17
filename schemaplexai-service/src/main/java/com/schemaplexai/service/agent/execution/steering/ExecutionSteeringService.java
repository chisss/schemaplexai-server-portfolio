package com.schemaplexai.service.agent.execution.steering;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.schemaplexai.dao.mapper.ExecutionSteeringMapper;
import com.schemaplexai.model.entity.ExecutionSteering;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 执行中用户引导（Steering）服务。
 *
 * <p>对齐 Codex interrupt 语义：新引导以追加消息注入同一会话，原任务与上下文不丢失。
 * 消费方（Agent 主循环轮边界 / Team 恢复边界）按 seq 升序应用，CAS 防重复注入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionSteeringService {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPLIED = "applied";
    public static final String STATUS_DISCARDED = "discarded";

    /**
     * 引导注入包装提示词：最新引导最高优先级，此前任务与已完成工作全部保留
     */
    public static String buildSteeringWrapper(String content) {
        return "【用户最新引导】以下是用户在本任务执行过程中补充的最新引导信息，"
                + "请以该引导为最高优先级继续工作；此前任务目标与已完成的全部工作保持不变。\n"
                + "引导内容：\n" + content;
    }

    private final ExecutionSteeringMapper steeringMapper;

    /**
     * 投递一条引导（运行中/暂停中均可投递），seq 在同执行内单调递增
     */
    public ExecutionSteering create(String tenantId, String executionId, String content) {
        if (!StringUtils.hasText(executionId) || !StringUtils.hasText(content)) {
            throw new IllegalArgumentException("executionId 与引导内容不能为空");
        }
        ExecutionSteering latest = steeringMapper.selectOne(new LambdaQueryWrapper<ExecutionSteering>()
                .eq(ExecutionSteering::getExecutionId, executionId)
                .orderByDesc(ExecutionSteering::getSeq)
                .last("LIMIT 1"));
        int nextSeq = latest != null && latest.getSeq() != null ? latest.getSeq() + 1 : 1;

        ExecutionSteering steering = new ExecutionSteering();
        steering.setTenantId(tenantId);
        steering.setExecutionId(executionId);
        steering.setSeq(nextSeq);
        steering.setContent(content.trim());
        steering.setStatus(STATUS_PENDING);
        steeringMapper.insert(steering);
        log.info("投递执行引导: executionId={}, seq={}, steeringId={}", executionId, nextSeq, steering.getId());
        return steering;
    }

    /**
     * 查询待注入引导（seq 升序）
     */
    public List<ExecutionSteering> findPending(String executionId) {
        if (!StringUtils.hasText(executionId)) {
            return List.of();
        }
        return steeringMapper.selectList(new LambdaQueryWrapper<ExecutionSteering>()
                .eq(ExecutionSteering::getExecutionId, executionId)
                .eq(ExecutionSteering::getStatus, STATUS_PENDING)
                .orderByAsc(ExecutionSteering::getSeq));
    }

    /**
     * 一次性应用：pending→applied CAS，重复消费返回 false
     */
    public boolean markApplied(String steeringId) {
        ExecutionSteering update = new ExecutionSteering();
        update.setStatus(STATUS_APPLIED);
        update.setAppliedAt(LocalDateTime.now());
        int rows = steeringMapper.update(update, new LambdaUpdateWrapper<ExecutionSteering>()
                .eq(ExecutionSteering::getId, steeringId)
                .eq(ExecutionSteering::getStatus, STATUS_PENDING));
        return rows == 1;
    }

    /**
     * 批量丢弃（执行结束清理）
     */
    public void markDiscardedByExecution(String executionId) {
        ExecutionSteering update = new ExecutionSteering();
        update.setStatus(STATUS_DISCARDED);
        steeringMapper.update(update, new LambdaUpdateWrapper<ExecutionSteering>()
                .eq(ExecutionSteering::getExecutionId, executionId)
                .eq(ExecutionSteering::getStatus, STATUS_PENDING));
    }
}
