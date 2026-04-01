package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.SecurityIncidentAction;
import org.apache.ibatis.annotations.Mapper;

/**
 * 安全风控事件动作 Mapper
 */
@Mapper
public interface SecurityIncidentActionMapper extends BaseMapper<SecurityIncidentAction> {
}
