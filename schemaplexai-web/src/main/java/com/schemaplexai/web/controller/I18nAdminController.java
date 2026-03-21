package com.schemaplexai.web.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.entity.I18nLocale;
import com.schemaplexai.model.entity.I18nMessage;
import com.schemaplexai.service.i18n.I18nAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 国际化管理控制器（管理端 CRUD）
 */
@RestController
@RequestMapping("/system/i18n")
@RequiredArgsConstructor
@Tag(name = "国际化管理")
public class I18nAdminController {

    private final I18nAdminService i18nAdminService;

    // ── 语言管理 ──────────────────────────────────────────────────────────────

    @GetMapping("/locales")
    @Operation(summary = "获取所有语言")
    public R<List<I18nLocale>> listLocales() {
        return R.ok(i18nAdminService.listLocales());
    }

    @PostMapping("/locales")
    @Operation(summary = "新增语言")
    public R<I18nLocale> createLocale(@RequestBody I18nLocale locale) {
        return R.ok(i18nAdminService.createLocale(locale));
    }

    @PutMapping("/locales/{id}")
    @Operation(summary = "更新语言")
    public R<I18nLocale> updateLocale(@PathVariable Long id, @RequestBody I18nLocale locale) {
        return R.ok(i18nAdminService.updateLocale(id, locale));
    }

    @DeleteMapping("/locales/{id}")
    @Operation(summary = "删除语言")
    public R<Void> deleteLocale(@PathVariable Long id) {
        i18nAdminService.deleteLocale(id);
        return R.ok();
    }

    // ── 文案管理 ──────────────────────────────────────────────────────────────

    @GetMapping("/messages")
    @Operation(summary = "分页查询文案")
    public R<Page<I18nMessage>> pageMessages(
            @RequestParam String locale,
            @RequestParam(required = false) String msgKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(i18nAdminService.pageMessages(locale, msgKey, page, size));
    }

    @PostMapping("/messages")
    @Operation(summary = "新增文案")
    public R<I18nMessage> createMessage(@RequestBody I18nMessage msg) {
        return R.ok(i18nAdminService.createMessage(msg));
    }

    @PutMapping("/messages/{id}")
    @Operation(summary = "更新文案")
    public R<I18nMessage> updateMessage(@PathVariable Long id, @RequestBody I18nMessage msg) {
        return R.ok(i18nAdminService.updateMessage(id, msg));
    }

    @DeleteMapping("/messages/{id}")
    @Operation(summary = "删除文案")
    public R<Void> deleteMessage(@PathVariable Long id) {
        i18nAdminService.deleteMessage(id);
        return R.ok();
    }
}
