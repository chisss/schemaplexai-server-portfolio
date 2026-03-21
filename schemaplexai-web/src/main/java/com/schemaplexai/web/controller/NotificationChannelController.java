package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.channel.NotificationChannelCreateRequest;
import com.schemaplexai.model.dto.channel.NotificationChannelQueryRequest;
import com.schemaplexai.model.dto.channel.NotificationChannelUpdateRequest;
import com.schemaplexai.model.vo.channel.NotificationChannelVO;
import com.schemaplexai.service.channel.NotificationChannelService;
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
 * 通知渠道管理控制器
 */
@RestController
@RequestMapping("/notification-channels")
@RequiredArgsConstructor
@Tag(name = "通知渠道管理")
public class NotificationChannelController {

    private final NotificationChannelService channelService;

    @PostMapping
    @Operation(summary = "创建通知渠道")
    public R<NotificationChannelVO> create(@Valid @RequestBody NotificationChannelCreateRequest request) {
        return R.ok(channelService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询通知渠道")
    public R<PageResult<NotificationChannelVO>> page(NotificationChannelQueryRequest request) {
        return R.ok(channelService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取通知渠道详情")
    public R<NotificationChannelVO> getById(@PathVariable String id) {
        return R.ok(channelService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新通知渠道")
    public R<NotificationChannelVO> update(@PathVariable String id,
                                            @Valid @RequestBody NotificationChannelUpdateRequest request) {
        return R.ok(channelService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除通知渠道")
    public R<Void> delete(@PathVariable String id) {
        channelService.delete(id);
        return R.ok();
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "测试通知渠道")
    public R<NotificationChannelVO> testChannel(@PathVariable String id) {
        return R.ok(channelService.testChannel(id));
    }
}
