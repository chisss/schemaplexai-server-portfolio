package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.RagConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * RAG 配置 Mapper
 */
@Mapper
public interface RagConfigMapper extends BaseMapper<RagConfig> {
}
