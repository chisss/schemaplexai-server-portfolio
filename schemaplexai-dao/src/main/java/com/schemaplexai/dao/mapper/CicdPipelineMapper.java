package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.CicdPipeline;
import org.apache.ibatis.annotations.Mapper;

/**
 * CICD Pipeline Mapper
 */
@Mapper
public interface CicdPipelineMapper extends BaseMapper<CicdPipeline> {
}
