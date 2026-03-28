package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 对话消息 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
}
