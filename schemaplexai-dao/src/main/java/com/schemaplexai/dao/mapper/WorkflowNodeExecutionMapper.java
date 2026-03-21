package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.WorkflowNodeExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 工作流节点执行记录 Mapper
 */
@Mapper
public interface WorkflowNodeExecutionMapper extends BaseMapper<WorkflowNodeExecution> {

    @Select("SELECT * FROM sf_workflow_node_execution WHERE instance_id = #{instanceId} ORDER BY created_at")
    List<WorkflowNodeExecution> selectByInstanceId(@Param("instanceId") String instanceId);
}
