package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.SecurityRuleItem;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全规则项 Mapper
 */
@Mapper
public interface SecurityRuleItemMapper extends BaseMapper<SecurityRuleItem> {
}
