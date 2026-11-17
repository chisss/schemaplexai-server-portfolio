package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.ExecutionSteering;
import org.apache.ibatis.annotations.Mapper;

/**
 * 执行中用户引导 Mapper
 */
@Mapper
public interface ExecutionSteeringMapper extends BaseMapper<ExecutionSteering> {
}
