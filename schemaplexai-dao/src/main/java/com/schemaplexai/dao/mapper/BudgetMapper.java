package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.Budget;
import org.apache.ibatis.annotations.Mapper;

/**
 * 预算 Mapper接口
 */
@Mapper
public interface BudgetMapper extends BaseMapper<Budget> {
}
