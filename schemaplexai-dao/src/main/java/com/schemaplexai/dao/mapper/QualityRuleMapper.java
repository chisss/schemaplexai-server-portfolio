package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.QualityRule;
import org.apache.ibatis.annotations.Mapper;

/**
 * 质量规则 Mapper接口
 */
@Mapper
public interface QualityRuleMapper extends BaseMapper<QualityRule> {
}
