package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.workflow.ReviewCommentCreateRequest;
import com.schemaplexai.model.dto.workflow.ReviewDecisionRequest;
import com.schemaplexai.model.dto.workflow.ReviewSessionCreateRequest;
import com.schemaplexai.model.vo.workflow.ReviewCommentVO;
import com.schemaplexai.model.vo.workflow.ReviewSessionVO;
import com.schemaplexai.service.workflow.ReviewSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 评审管理控制器
 */
@RestController
@RequestMapping("/reviews")
@RequiredArgsConstructor
@Tag(name = "评审管理")
public class ReviewController {

    private final ReviewSessionService reviewSessionService;

    @PostMapping("/sessions")
    @Operation(summary = "创建评审会话")
    public R<ReviewSessionVO> createSession(@Valid @RequestBody ReviewSessionCreateRequest request) {
        return R.ok(reviewSessionService.create(request));
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "获取评审会话详情（含汇总）")
    public R<ReviewSessionVO> getSession(@PathVariable String id) {
        return R.ok(reviewSessionService.getWithSummary(id));
    }

    @PostMapping("/sessions/{id}/comments")
    @Operation(summary = "提交评审意见")
    public R<ReviewCommentVO> submitComment(@PathVariable String id,
                                             @Valid @RequestBody ReviewCommentCreateRequest request) {
        return R.ok(reviewSessionService.submitComment(id, request));
    }

    @GetMapping("/sessions/my-pending")
    @Operation(summary = "获取我的待处理评审")
    public R<List<ReviewSessionVO>> getMyPending() {
        return R.ok(reviewSessionService.getMyPending());
    }

    @PostMapping("/sessions/{id}/approve")
    @Operation(summary = "审批通过评审任务")
    public R<ReviewSessionVO> approve(@PathVariable String id,
                                      @RequestBody(required = false) ReviewDecisionRequest request) {
        return R.ok(reviewSessionService.approve(id, request));
    }

    @PostMapping("/sessions/{id}/reject")
    @Operation(summary = "驳回评审任务")
    public R<ReviewSessionVO> reject(@PathVariable String id,
                                     @RequestBody(required = false) ReviewDecisionRequest request) {
        return R.ok(reviewSessionService.reject(id, request));
    }

    @PostMapping("/sessions/{id}/request-modify")
    @Operation(summary = "退回修改评审任务")
    public R<ReviewSessionVO> requestModify(@PathVariable String id,
                                            @RequestBody(required = false) ReviewDecisionRequest request) {
        return R.ok(reviewSessionService.requestModify(id, request));
    }
}
