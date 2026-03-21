package com.schemaplexai.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.WebhookEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * Webhook事件 Mapper接口
 */
@Mapper
public interface WebhookEventMapper extends BaseMapper<WebhookEvent> {
}
