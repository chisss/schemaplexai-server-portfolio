package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.SecurityPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全策略 Mapper
 */
@Mapper
public interface SecurityPolicyMapper extends BaseMapper<SecurityPolicy> {
}
