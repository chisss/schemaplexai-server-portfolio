package com.schemaplexai.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PluginInstallStatus {

    PENDING_VERIFY("pending_verify", "待签名校验"),
    VERIFIED("verified", "签名已通过"),
    COMPATIBLE("compatible", "兼容性已通过"),
    INSTALLED("installed", "已安装"),
    UNINSTALLED("uninstalled", "已卸载"),
    FAILED("failed", "安装失败");

    private final String code;
    private final String description;
}
