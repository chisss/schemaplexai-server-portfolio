package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.agent.AgentTeamMemberToolBindingDTO;
import com.schemaplexai.model.vo.agent.AgentTeamMemberToolBindingVO;
import com.schemaplexai.service.agent.AgentMemberToolService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Agent 团队成员工具绑定控制器
 */
@RestController
@RequestMapping("/agent-members")
@RequiredArgsConstructor
@Tag(name = "Agent成员工具绑定")
public class AgentMemberToolController {

    private final AgentMemberToolService agentMemberToolService;

    @GetMapping("/{memberId}/tools")
    @Operation(summary = "获取团队成员工具绑定")
    public R<List<AgentTeamMemberToolBindingVO>> getMemberTools(@PathVariable String memberId) {
        return R.ok(agentMemberToolService.getMemberToolBindings(memberId));
    }

    @PutMapping("/{memberId}/tools")
    @Operation(summary = "保存团队成员工具绑定（全量覆盖）")
    public R<List<AgentTeamMemberToolBindingVO>> saveMemberTools(
            @PathVariable String memberId,
            @Valid @RequestBody List<@Valid AgentTeamMemberToolBindingDTO> request) {
        return R.ok(agentMemberToolService.saveMemberToolBindings(memberId, request));
    }

    @DeleteMapping("/{memberId}/tools/{bindingId}")
    @Operation(summary = "删除团队成员工具绑定")
    public R<Void> deleteMemberTool(@PathVariable String memberId, @PathVariable String bindingId) {
        agentMemberToolService.getMemberToolBindings(memberId);
        agentMemberToolService.deleteMemberToolBinding(bindingId);
        return R.ok();
    }
}
