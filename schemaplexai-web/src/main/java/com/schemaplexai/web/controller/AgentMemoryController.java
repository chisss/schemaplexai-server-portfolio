package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.entity.AgentMemory;
import com.schemaplexai.service.agent.memory.AgentMemoryExtractionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent记忆管理控制器
 */
@RestController
@RequestMapping("/agent-memories")
@RequiredArgsConstructor
@Tag(name = "Agent记忆管理")
public class AgentMemoryController {

    private final AgentMemoryExtractionService memoryService;

    @GetMapping
    @Operation(summary = "查询Agent的记忆列表")
    public R<List<AgentMemory>> list(@RequestParam String agentId) {
        String tenantId = SecurityUtil.getCurrentTenantId();
        return R.ok(memoryService.listMemories(tenantId, agentId));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除Agent记忆")
    public R<Void> delete(@PathVariable String id) {
        memoryService.deleteMemory(id);
        return R.ok();
    }
}
