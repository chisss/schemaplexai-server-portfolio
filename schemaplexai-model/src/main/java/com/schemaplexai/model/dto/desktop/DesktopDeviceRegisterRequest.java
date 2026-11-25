package com.schemaplexai.model.dto.desktop;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 桌面设备登记，不接受客户端提供用户或租户身份。 */
public record DesktopDeviceRegisterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 30) String platform,
        boolean supportsExactEdit,
        boolean supportsReadRange) {
}
