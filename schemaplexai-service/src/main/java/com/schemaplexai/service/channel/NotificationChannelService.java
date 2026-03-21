package com.schemaplexai.service.channel;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.model.dto.channel.NotificationChannelCreateRequest;
import com.schemaplexai.model.dto.channel.NotificationChannelQueryRequest;
import com.schemaplexai.model.dto.channel.NotificationChannelUpdateRequest;
import com.schemaplexai.model.vo.channel.NotificationChannelVO;

/**
 * 通知渠道服务接口
 */
public interface NotificationChannelService {

    NotificationChannelVO create(NotificationChannelCreateRequest request);

    PageResult<NotificationChannelVO> page(NotificationChannelQueryRequest request);

    NotificationChannelVO getById(String id);

    NotificationChannelVO update(String id, NotificationChannelUpdateRequest request);

    void delete(String id);

    NotificationChannelVO testChannel(String id);
}
