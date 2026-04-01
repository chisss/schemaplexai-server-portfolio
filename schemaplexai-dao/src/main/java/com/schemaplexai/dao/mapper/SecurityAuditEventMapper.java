package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.SecurityAuditEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全审计事件 Mapper
 */
@Mapper
public interface SecurityAuditEventMapper extends BaseMapper<SecurityAuditEvent> {
}
