package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.ContextRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 上下文关联关系 Mapper
 */
@Mapper
public interface ContextRelationMapper extends BaseMapper<ContextRelation> {
}
