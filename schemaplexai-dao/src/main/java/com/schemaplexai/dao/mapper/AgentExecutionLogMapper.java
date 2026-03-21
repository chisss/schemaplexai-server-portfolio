package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.AgentExecutionLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 执行日志 Mapper
 */
@Mapper
public interface AgentExecutionLogMapper extends BaseMapper<AgentExecutionLog> {
}
