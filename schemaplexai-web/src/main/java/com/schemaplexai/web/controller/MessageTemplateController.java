package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.message.MessageTemplateCreateRequest;
import com.schemaplexai.model.dto.message.MessageTemplateQueryRequest;
import com.schemaplexai.model.dto.message.MessageTemplateUpdateRequest;
import com.schemaplexai.model.vo.message.MessageTemplateMetadataVO;
import com.schemaplexai.model.vo.message.MessageTemplateVO;
import com.schemaplexai.service.message.MessageTemplateService;
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

import java.util.List;

/**
 * 消息模板管理控制器
 */
@RestController
@RequestMapping("/message-templates")
@RequiredArgsConstructor
@Tag(name = "消息模板管理")
public class MessageTemplateController {

    private final MessageTemplateService messageTemplateService;

    @PostMapping
    @Operation(summary = "创建消息模板")
    public R<MessageTemplateVO> create(@Valid @RequestBody MessageTemplateCreateRequest request) {
        return R.ok(messageTemplateService.create(request));
    }

    @GetMapping
    @Operation(summary = "分页查询消息模板")
    public R<PageResult<MessageTemplateVO>> page(MessageTemplateQueryRequest request) {
        return R.ok(messageTemplateService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取消息模板详情")
    public R<MessageTemplateVO> getById(@PathVariable String id) {
        return R.ok(messageTemplateService.getById(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新消息模板")
    public R<MessageTemplateVO> update(@PathVariable String id,
                                       @Valid @RequestBody MessageTemplateUpdateRequest request) {
        return R.ok(messageTemplateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除消息模板")
    public R<Void> delete(@PathVariable String id) {
        messageTemplateService.delete(id);
        return R.ok();
    }

    @GetMapping("/definitions")
    @Operation(summary = "获取消息模板元数据定义")
    public R<List<MessageTemplateMetadataVO>> definitions() {
        return R.ok(messageTemplateService.listDefinitions());
    }
}
