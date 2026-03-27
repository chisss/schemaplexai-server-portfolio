package com.schemaplexai.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.schemaplexai.common.result.R;
import com.schemaplexai.dao.mapper.BuiltinToolMapper;
import com.schemaplexai.model.entity.BuiltinTool;
import com.schemaplexai.model.vo.agent.BuiltinToolVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 系统内置工具控制器
 */
@RestController
@RequestMapping("/tools")
@RequiredArgsConstructor
@Tag(name = "内置工具")
public class BuiltinToolController {

    private final BuiltinToolMapper builtinToolMapper;

    @GetMapping("/builtin")
    @Operation(summary = "获取内置工具列表")
    public R<List<BuiltinToolVO>> listBuiltinTools() {
        var tools = builtinToolMapper.selectList(
                new LambdaQueryWrapper<BuiltinTool>()
                        .eq(BuiltinTool::getEnabled, true)
                        .orderByAsc(BuiltinTool::getSortOrder));
        return R.ok(tools.stream().map(this::toVO).toList());
    }

    private BuiltinToolVO toVO(BuiltinTool tool) {
        var vo = new BuiltinToolVO();
        vo.setId(tool.getId());
        vo.setCode(tool.getCode());
        vo.setName(tool.getName());
        vo.setDescription(tool.getDescription());
        vo.setInputSchema(tool.getInputSchema());
        vo.setOsSupport(tool.getOsSupport());
        vo.setEnabled(tool.getEnabled());
        vo.setSortOrder(tool.getSortOrder());
        vo.setCreatedAt(tool.getCreatedAt());
        vo.setUpdatedAt(tool.getUpdatedAt());
        return vo;
    }
}
