package com.schemaplexai.model.vo.context;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 上下文条目VO
 */
@Data
public class ContextItemVO {

    private String id;

    private String contextId;

    private String itemType;

    private String title;

    private String content;

    private String sourceUrl;

    private Map<String, Object> metadata;

    private Integer tokenCount;

    private Integer sortOrder;

    private LocalDateTime createdAt;
}
