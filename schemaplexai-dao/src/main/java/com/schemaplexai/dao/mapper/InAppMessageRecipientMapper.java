package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.InAppMessageRecipient;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内信接收人 Mapper
 */
@Mapper
public interface InAppMessageRecipientMapper extends BaseMapper<InAppMessageRecipient> {
}
