package com.schemaplexai.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * 统一工具执行结果
 */
@Data
@Builder
public class ToolResult {

    private String callId;
    private String toolCode;
    private boolean success;
    private JsonNode result;
    private String errorMessage;
    private String controlAction;

    /** 本机工具桥验收的回执关联标识，仅供服务端归档。 */
    @JsonIgnore
    private UUID desktopCallId;
}
