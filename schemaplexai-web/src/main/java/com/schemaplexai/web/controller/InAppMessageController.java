package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.notification.InAppMessageQueryRequest;
import com.schemaplexai.model.vo.notification.InAppMessageVO;
import com.schemaplexai.service.notification.InAppMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站内信控制器
 */
@RestController
@RequestMapping("/in-app-messages")
@RequiredArgsConstructor
@Tag(name = "站内信")
public class InAppMessageController {

    private final InAppMessageService inAppMessageService;

    @GetMapping("/inbox")
    @Operation(summary = "分页查询我的站内信")
    public R<PageResult<InAppMessageVO>> pageInbox(InAppMessageQueryRequest request) {
        return R.ok(inAppMessageService.pageInbox(request));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "获取未读站内信数量")
    public R<Long> unreadCount() {
        return R.ok(inAppMessageService.countUnread());
    }

    @PostMapping("/{recipientId}/read")
    @Operation(summary = "标记站内信为已读")
    public R<Void> markRead(@PathVariable String recipientId) {
        inAppMessageService.markRead(recipientId);
        return R.ok();
    }

    @PostMapping("/{recipientId}/archive")
    @Operation(summary = "归档站内信")
    public R<Void> archive(@PathVariable String recipientId) {
        inAppMessageService.archive(recipientId);
        return R.ok();
    }
}
