package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.notification.NotificationRecordQueryRequest;
import com.schemaplexai.model.vo.notification.NotificationRecordVO;
import com.schemaplexai.service.notification.NotificationRecordService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 通知记录控制器
 */
@RestController
@RequestMapping("/notification-records")
@RequiredArgsConstructor
@Tag(name = "通知记录管理")
public class NotificationRecordController {

    private final NotificationRecordService notificationRecordService;

    @GetMapping
    @Operation(summary = "分页查询通知记录")
    public R<PageResult<NotificationRecordVO>> page(NotificationRecordQueryRequest request) {
        return R.ok(notificationRecordService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询通知记录详情")
    public R<NotificationRecordVO> getById(@PathVariable String id) {
        return R.ok(notificationRecordService.getById(id));
    }
}
