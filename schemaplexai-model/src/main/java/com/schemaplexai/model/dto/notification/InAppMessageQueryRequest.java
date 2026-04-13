package com.schemaplexai.model.dto.notification;

import lombok.Data;

/**
 * 站内信收件箱查询
 */
@Data
public class InAppMessageQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    /** unread/read/archived */
    private String status;

    private String keyword;
}
