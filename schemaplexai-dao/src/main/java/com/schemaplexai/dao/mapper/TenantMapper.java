package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.Tenant;
import org.apache.ibatis.annotations.Mapper;

/**
 * 租户 Mapper接口
 */
@Mapper
public interface TenantMapper extends BaseMapper<Tenant> {
}
