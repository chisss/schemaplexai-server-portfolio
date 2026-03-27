package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.TenantRuntimePolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户运行时策略 Mapper
 */
@Mapper
public interface TenantRuntimePolicyMapper extends BaseMapper<TenantRuntimePolicy> {
}
