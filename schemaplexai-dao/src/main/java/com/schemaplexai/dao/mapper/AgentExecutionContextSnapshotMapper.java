package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.AgentExecutionContextSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 执行上下文快照 Mapper
 */
@Mapper
public interface AgentExecutionContextSnapshotMapper extends BaseMapper<AgentExecutionContextSnapshot> {
}
