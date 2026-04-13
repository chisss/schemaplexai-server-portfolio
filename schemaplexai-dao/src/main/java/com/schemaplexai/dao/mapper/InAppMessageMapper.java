package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.InAppMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内信主表 Mapper
 */
@Mapper
public interface InAppMessageMapper extends BaseMapper<InAppMessage> {
}
