package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.EvalResult;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评估结果 Mapper
 */
@Mapper
public interface EvalResultMapper extends BaseMapper<EvalResult> {
}
