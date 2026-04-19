package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * HTTP 请求方法枚举
 */
@Getter
@AllArgsConstructor
public enum HttpMethodEnum {

    GET("GET"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
    PATCH("PATCH");

    private final String code;

    /**
     * 根据字符串解析 HTTP 方法，不匹配时默认 POST
     */
    public static HttpMethodEnum fromCode(String code) {
        if (code == null) {
            return POST;
        }
        for (HttpMethodEnum m : values()) {
            if (m.code.equalsIgnoreCase(code.trim())) {
                return m;
            }
        }
        return POST;
    }
}
