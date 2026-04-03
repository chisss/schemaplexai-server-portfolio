package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.agent.AgentTeamMemberContextBindingDTO;
import com.schemaplexai.model.vo.agent.AgentTeamMemberContextBindingVO;
import com.schemaplexai.service.agent.AgentMemberContextService;
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
 * Team 成员上下文绑定控制器
 */
@RestController
@RequestMapping("/agent-members")
@RequiredArgsConstructor
@Tag(name = "Agent成员上下文绑定")
public class AgentMemberContextController {

    private final AgentMemberContextService agentMemberContextService;

    @GetMapping("/{memberId}/contexts")
    @Operation(summary = "获取团队成员上下文绑定")
    public R<List<AgentTeamMemberContextBindingVO>> getMemberContexts(@PathVariable String memberId) {
        return R.ok(agentMemberContextService.getMemberContextBindings(memberId));
    }

    @PutMapping("/{memberId}/contexts")
    @Operation(summary = "保存团队成员上下文绑定（全量覆盖）")
    public R<List<AgentTeamMemberContextBindingVO>> saveMemberContexts(
            @PathVariable String memberId,
            @Valid @RequestBody List<@Valid AgentTeamMemberContextBindingDTO> request) {
        return R.ok(agentMemberContextService.saveMemberContextBindings(memberId, request));
    }

    @DeleteMapping("/{memberId}/contexts/{bindingId}")
    @Operation(summary = "删除团队成员上下文绑定")
    public R<Void> deleteMemberContext(@PathVariable String memberId, @PathVariable String bindingId) {
        agentMemberContextService.getMemberContextBindings(memberId);
        agentMemberContextService.deleteMemberContextBinding(bindingId);
        return R.ok();
    }
}
