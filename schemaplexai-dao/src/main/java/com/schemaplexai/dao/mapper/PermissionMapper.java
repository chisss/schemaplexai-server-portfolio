package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.Permission;
import org.apache.ibatis.annotations.Mapper;

/**
 * Permission Mapper接口
 */
@Mapper
public interface PermissionMapper extends BaseMapper<Permission> {
}
