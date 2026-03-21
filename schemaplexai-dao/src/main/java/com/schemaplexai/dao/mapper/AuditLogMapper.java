package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * AuditLog Mapper接口
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
