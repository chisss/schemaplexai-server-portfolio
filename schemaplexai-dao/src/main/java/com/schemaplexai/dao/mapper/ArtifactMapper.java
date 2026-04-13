package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.Artifact;
import org.apache.ibatis.annotations.Mapper;

/**
 * 统一产物 Mapper
 */
@Mapper
public interface ArtifactMapper extends BaseMapper<Artifact> {
}
