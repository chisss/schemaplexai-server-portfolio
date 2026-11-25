package com.schemaplexai.model.dto.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 本机执行结果，调用归属由服务端已投递记录决定。 */
public record DesktopToolResultRequest(
        @NotNull Boolean success,
        JsonNode output,
        @Size(max = 4000) String error) {
}
