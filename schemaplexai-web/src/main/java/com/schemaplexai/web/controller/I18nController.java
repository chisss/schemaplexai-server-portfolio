package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.entity.I18nLocale;
import com.schemaplexai.service.i18n.I18nService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 国际化控制器
 */
@RestController
@RequestMapping("/i18n")
@RequiredArgsConstructor
@Tag(name = "国际化")
public class I18nController {

    private final I18nService i18nService;

    @GetMapping("/locales")
    @Operation(summary = "获取支持的语言列表")
    public R<List<I18nLocale>> getLocales() {
        return R.ok(i18nService.getLocales());
    }

    @GetMapping("/messages")
    @Operation(summary = "获取翻译文案")
    public R<Map<String, Object>> getMessages(@RequestParam String locale) {
        return R.ok(i18nService.getMessages(locale));
    }
}
