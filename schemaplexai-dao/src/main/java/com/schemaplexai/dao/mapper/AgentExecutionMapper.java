package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.AgentExecution;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 执行记录 Mapper
 */
@Mapper
public interface AgentExecutionMapper extends BaseMapper<AgentExecution> {
}
