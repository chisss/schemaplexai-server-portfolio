package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.agent.ConversationRollbackRequest;
import com.schemaplexai.service.agent.execution.ConversationRollbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话管理控制器
 */
@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
@Tag(name = "对话管理")
public class ConversationController {

    private final ConversationRollbackService rollbackService;

    @PostMapping("/{conversationId}/rollback")
    @Operation(summary = "回滚对话到指定轮次")
    public R<Integer> rollback(@PathVariable String conversationId,
                               @Valid @RequestBody ConversationRollbackRequest request) {
        int deleted = rollbackService.rollback(conversationId, request.getTargetTurnIndex());
        return R.ok(deleted);
    }
}
