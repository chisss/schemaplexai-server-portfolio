package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.SecurityIncident;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全风控事件 Mapper
 */
@Mapper
public interface SecurityIncidentMapper extends BaseMapper<SecurityIncident> {
}
