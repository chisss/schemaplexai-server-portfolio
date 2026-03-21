package com.schemaplexai.model.dto.channel;

import lombok.Data;

/**
 * 通知渠道查询请求
 */
@Data
public class NotificationChannelQueryRequest {

    /** 当前页码 */
    private Integer page = 1;

    /** 每页大小 */
    private Integer size = 20;

    /** 渠道类型 */
    private String channelType;

    /** 状态 */
    private String status;

    /** 关键字搜索 */
    private String keyword;
}
