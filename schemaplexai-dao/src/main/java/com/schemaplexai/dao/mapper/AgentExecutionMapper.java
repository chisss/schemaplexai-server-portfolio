package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.AgentExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Agent 执行记录 Mapper
 */
@Mapper
public interface AgentExecutionMapper extends BaseMapper<AgentExecution> {

    /**
     * 会话消息版本乐观锁 CAS：仅当版本等于期望值时自增，返回影响行数（1=成功，0=冲突）
     */
    @Update("UPDATE sf_agent_execution SET chat_version = chat_version + 1 "
            + "WHERE id = #{id} AND chat_version = #{expectedVersion}")
    int casIncrementChatVersion(@Param("id") String id, @Param("expectedVersion") int expectedVersion);
}
