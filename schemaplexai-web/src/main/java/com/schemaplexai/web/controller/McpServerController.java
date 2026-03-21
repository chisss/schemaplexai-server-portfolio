package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.mcp.McpServerCreateRequest;
import com.schemaplexai.model.dto.mcp.McpServerQueryRequest;
import com.schemaplexai.model.dto.mcp.McpServerUpdateRequest;
import com.schemaplexai.model.vo.mcp.McpServerVO;
import com.schemaplexai.service.mcp.McpServerService;
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

/**
 * MCP Server管理控制器
 */
@RestController
@RequestMapping("/mcp-servers")
@RequiredArgsConstructor
@Tag(name = "MCP Server管理")
public class McpServerController {

    private final McpServerService mcpServerService;

    @PostMapping
    @Operation(summary = "注册MCP Server")
    public R<McpServerVO> create(@Valid @RequestBody McpServerCreateRequest request) {
        return R.ok(mcpServerService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询MCP Server列表")
    public R<PageResult<McpServerVO>> page(McpServerQueryRequest request) {
        return R.ok(mcpServerService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取MCP Server详情")
    public R<McpServerVO> getById(@PathVariable String id) {
        return R.ok(mcpServerService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新MCP Server")
    public R<McpServerVO> update(@PathVariable String id,
                                  @Valid @RequestBody McpServerUpdateRequest request) {
        return R.ok(mcpServerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除MCP Server")
    public R<Void> delete(@PathVariable String id) {
        mcpServerService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/health-check")
    @Operation(summary = "健康检查")
    public R<McpServerVO> healthCheck(@PathVariable String id) {
        return R.ok(mcpServerService.healthCheck(id));
    }

    @PostMapping("/{id}/discover-tools")
    @Operation(summary = "发现工具列表")
    public R<McpServerVO> discoverTools(@PathVariable String id) {
        return R.ok(mcpServerService.discoverTools(id));
    }
}
