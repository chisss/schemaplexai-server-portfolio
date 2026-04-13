package com.schemaplexai.model.dto.notification;

import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 通知记录查询请求
 */
@Data
public class NotificationRecordQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    private String keyword;

    private String channelType;

    private String status;

    private String templateType;

    private String sourceType;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sentAtStart;

    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sentAtEnd;
}
