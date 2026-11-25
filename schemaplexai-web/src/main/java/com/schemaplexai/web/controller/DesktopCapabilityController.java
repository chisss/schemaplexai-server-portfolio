package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.service.desktop.DesktopCapabilityService;
import com.schemaplexai.service.desktop.DesktopCapabilityService.Bootstrap;
import com.schemaplexai.service.desktop.DesktopCapabilityService.Card;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@Validated
@RestController
@RequestMapping("/desktop")
@RequiredArgsConstructor
public class DesktopCapabilityController {
    private final DesktopCapabilityService catalog;

    public record Grant(@NotBlank @Size(max = 64) String userId) {}

    @PutMapping("/catalog/{type}/{id}/grants")
    @PreAuthorize("@authorizationService.hasPermission('agent:manage')")
    public R<Void> grant(@PathVariable String type, @PathVariable String id, @Valid @RequestBody Grant request) {
        catalog.grant(type, id, request.userId());
        return R.ok();
    }

    @DeleteMapping("/catalog/{type}/{id}/grants/{userId}")
    @PreAuthorize("@authorizationService.hasPermission('agent:manage')")
    public R<Void> revoke(@PathVariable String type, @PathVariable String id, @PathVariable String userId) {
        catalog.revoke(type, id, userId);
        return R.ok();
    }

    @GetMapping("/bootstrap")
    public R<Bootstrap> bootstrap() {
        return R.ok(catalog.bootstrap());
    }

    @GetMapping("/catalog/experts")
    public R<PageResult<Card>> experts(@RequestParam(required = false) @Size(max = 100) String keyword,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
                                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(catalog.experts(keyword, type, page, size));
    }

    @GetMapping("/catalog/experts/{id}")
    public R<Card> expert(@PathVariable String id) {
        return R.ok(catalog.expert(id));
    }

    @GetMapping("/catalog/skills")
    public R<PageResult<Card>> skills(@RequestParam(required = false) @Size(max = 100) String keyword,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(defaultValue = "1") @Min(1) @Max(10000) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(catalog.skills(keyword, category, page, size));
    }

    @GetMapping("/catalog/skills/{id}")
    public R<Card> skill(@PathVariable String id) {
        return R.ok(catalog.skill(id));
    }
}
