package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.ExecutionSteering;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 执行中用户引导 Mapper
 */
@Mapper
public interface ExecutionSteeringMapper extends BaseMapper<ExecutionSteering> {

    /**
     * 原子插入并生成同执行内单调递增的 seq。
     *
     * <p>pg_advisory_xact_lock 按 execution_id 哈希加事务级咨询锁，锁持有期间
     * MAX(seq)+1 的读取串行化，并发投递不会产生重复 seq。锁在语句事务结束时释放，
     * 无需调用方显式开启事务。FROM 子查询保证无历史行时仍返回一行。
     */
    @Insert("""
            INSERT INTO sf_execution_steering
                (id, tenant_id, execution_id, seq, content, status, created_at, updated_at, deleted)
            SELECT #{id}, #{tenantId}, #{executionId},
                   COALESCE((SELECT MAX(seq) FROM sf_execution_steering WHERE execution_id = #{executionId}), 0) + 1,
                   #{content}, 'pending', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0
            FROM (SELECT pg_advisory_xact_lock(hashtext(#{executionId}::text))) lock_row
            """)
    int insertWithAutoSeq(@Param("id") String id,
                          @Param("tenantId") String tenantId,
                          @Param("executionId") String executionId,
                          @Param("content") String content);
}
