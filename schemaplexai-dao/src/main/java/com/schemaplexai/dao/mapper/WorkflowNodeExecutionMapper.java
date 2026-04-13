package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 工作流节点执行记录 Mapper
 */
@Mapper
public interface WorkflowNodeExecutionMapper extends BaseMapper<WorkflowNodeExecution> {

    List<WorkflowNodeExecution> selectByInstanceId(String instanceId);
}
