package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.desktop.DesktopDeviceRegisterRequest;
import com.schemaplexai.model.dto.desktop.DesktopToolResultRequest;
import com.schemaplexai.model.vo.desktop.DesktopDeviceVO;
import com.schemaplexai.model.vo.desktop.DesktopToolCallVO;
import com.schemaplexai.service.desktop.DesktopToolBroker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 企业员工设备执行通道，仅接收属于当前用户的服务端派发调用。 */
@RestController
@RequestMapping("/desktop/devices")
@RequiredArgsConstructor
@Validated
@PreAuthorize("@authorizationService.hasPermission(T(com.schemaplexai.common.enums.PermissionCodeEnum).AGENT_EXECUTE.getCode())")
public class DesktopDeviceController {

    private final DesktopToolBroker broker;

    @PostMapping
    public R<DesktopDeviceVO> register(@Valid @RequestBody DesktopDeviceRegisterRequest request) {
        return R.ok(broker.register(SecurityUtil.getCurrentTenantId(), SecurityUtil.getCurrentUserId(),
                request.supportsExactEdit(), request.supportsReadRange()));
    }

    @GetMapping("/{deviceId}/calls")
    public R<List<DesktopToolCallVO>> pending(@PathVariable String deviceId) {
        return R.ok(broker.takePending(SecurityUtil.getCurrentTenantId(), SecurityUtil.getCurrentUserId(), deviceId));
    }

    @PostMapping("/{deviceId}/calls/{callId}/result")
    public R<Void> complete(@PathVariable String deviceId, @PathVariable String callId,
                            @Valid @RequestBody DesktopToolResultRequest request) {
        broker.complete(SecurityUtil.getCurrentTenantId(), SecurityUtil.getCurrentUserId(), deviceId, callId, request);
        return R.ok();
    }

    @GetMapping("/{deviceId}/calls/{callId}")
    public R<Void> validateCall(@PathVariable String deviceId, @PathVariable String callId) {
        broker.validateCall(SecurityUtil.getCurrentTenantId(), SecurityUtil.getCurrentUserId(), deviceId, callId);
        return R.ok();
    }

    @DeleteMapping("/{deviceId}")
    public R<Void> unregister(@PathVariable String deviceId) {
        broker.unregister(SecurityUtil.getCurrentTenantId(), SecurityUtil.getCurrentUserId(), deviceId);
        return R.ok();
    }
}
