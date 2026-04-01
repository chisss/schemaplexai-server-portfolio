package com.schemaplexai.model.dto.security;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 安全绑定关系保存请求
 */
@Data
public class SecurityBindingSaveRequest {

    private List<Item> bindings;

    @Data
    public static class Item {
        private String bindingType;
        private String targetId;
        private String targetName;
        private Integer priority;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
        private Map<String, Object> bindingConfig;
    }
}
