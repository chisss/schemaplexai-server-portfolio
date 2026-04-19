package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.gateway.ApiGatewayCreateRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayLogQueryRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayPolicyRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayQueryRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayTestRequest;
import com.schemaplexai.model.dto.gateway.ApiGatewayUpdateRequest;
import com.schemaplexai.model.vo.gateway.ApiGatewayLogVO;
import com.schemaplexai.model.vo.gateway.ApiGatewayPolicyVO;
import com.schemaplexai.model.vo.gateway.ApiGatewayTestResult;
import com.schemaplexai.model.vo.gateway.ApiGatewayVO;
import com.schemaplexai.service.gateway.ApiGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api-gateways")
@RequiredArgsConstructor
@Tag(name = "API网关管理")
public class ApiGatewayController {

    private final ApiGatewayService apiGatewayService;

    @PostMapping
    @Operation(summary = "创建API网关")
    public R<ApiGatewayVO> create(@Valid @RequestBody ApiGatewayCreateRequest request) {
        return R.ok(apiGatewayService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询API网关列表")
    public R<PageResult<ApiGatewayVO>> page(ApiGatewayQueryRequest request) {
        return R.ok(apiGatewayService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取API网关详情")
    public R<ApiGatewayVO> getById(@PathVariable String id) {
        return R.ok(apiGatewayService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新API网关")
    public R<ApiGatewayVO> update(@PathVariable String id,
                                   @Valid @RequestBody ApiGatewayUpdateRequest request) {
        return R.ok(apiGatewayService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除API网关")
    public R<Void> delete(@PathVariable String id) {
        apiGatewayService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "测试API调用")
    public R<ApiGatewayTestResult> test(@PathVariable String id,
                                        @RequestBody(required = false) ApiGatewayTestRequest request) {
        return R.ok(apiGatewayService.test(id, request));
    }

    @PostMapping("/{id}/execute")
    @Operation(summary = "代理执行API调用")
    public R<ApiGatewayTestResult> execute(@PathVariable String id,
                                            @RequestBody(required = false) ApiGatewayTestRequest request) {
        return R.ok(apiGatewayService.execute(id, request, "user", null));
    }

    @GetMapping("/{id}/policy")
    @Operation(summary = "获取API网关策略")
    public R<ApiGatewayPolicyVO> getPolicy(@PathVariable String id) {
        return R.ok(apiGatewayService.getPolicy(id));
    }

    @PutMapping("/{id}/policy")
    @Operation(summary = "更新API网关策略")
    public R<ApiGatewayPolicyVO> updatePolicy(@PathVariable String id,
                                               @Valid @RequestBody ApiGatewayPolicyRequest request) {
        return R.ok(apiGatewayService.updatePolicy(id, request));
    }

    @GetMapping("/{id}/logs")
    @Operation(summary = "查询API调用日志")
    public R<PageResult<ApiGatewayLogVO>> getLogs(@PathVariable String id,
                                                   ApiGatewayLogQueryRequest request) {
        return R.ok(apiGatewayService.getLogs(id, request));
    }

    @GetMapping("/available")
    @Operation(summary = "获取可用API网关列表")
    public R<List<ApiGatewayVO>> listAvailable() {
        return R.ok(apiGatewayService.listAvailable());
    }
}
