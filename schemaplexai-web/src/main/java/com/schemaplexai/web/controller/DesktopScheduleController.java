package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.service.desktop.DesktopScheduleRule;
import com.schemaplexai.service.desktop.DesktopScheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 员工侧桌面自助入口。
 *
 * <p>入口只声明「登录 + 个人自助权限」；真正的资源级授权在 service：
 * 所有查询按 {@code Employee.current()} 的租户与用户过滤，跨用户资源一律不可见。
 * 把细粒度判定留在服务层，是因为同一条端点常按业务类型对应不同权限码（如审批决策），
 * 注解无法表达，硬写只会与 service 口径漂移。
 */
@Validated
@PreAuthorize("@authorizationService.hasPermission('self:service')")
@RestController
@RequestMapping("/desktop/schedules")
@RequiredArgsConstructor
public class DesktopScheduleController {
    private final DesktopScheduleService schedules;

    public record Draft(@NotBlank @Size(max = 2000) String text, @NotBlank String timezone) {}
    public record Action(@NotNull Long expectedRevision, @NotBlank @Size(max = 128) String clientRequestId) {}

    @PostMapping("/draft")
    public R<?> draft(@Valid @RequestBody Draft request) {
        var rule = DesktopScheduleRule.draft(request.text(), request.timezone());
        if (rule == null) return R.ok(Map.of("prompt", request.text(), "missingFields", List.of("schedule", "target")));
        return R.ok(Map.of("schedule", rule, "prompt", request.text(), "nextRuns", rule.preview(Instant.now()),
                "missingFields", List.of("target")));
    }

    @PostMapping("/preview")
    public R<?> preview(@RequestBody DesktopScheduleRule rule) {
        return R.ok(Map.of("normalizedSchedule", rule, "nextRuns", rule.preview(Instant.now()),
                "overlapPolicy", "skip", "misfirePolicy", "skip"));
    }

    @GetMapping
    public R<?> list(@RequestParam(defaultValue = "1") @Min(1) int page,
                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(schedules.list(page, size));
    }

    @PostMapping
    public R<?> create(@Valid @RequestBody DesktopScheduleService.Definition definition) {
        return R.ok(schedules.create(definition));
    }

    @GetMapping("/{id}")
    public R<?> get(@PathVariable String id) {
        return R.ok(schedules.get(id));
    }

    @PatchMapping("/{id}")
    public R<?> update(@PathVariable String id, @Valid @RequestBody DesktopScheduleService.Definition definition) {
        return R.ok(schedules.update(id, definition));
    }

    @DeleteMapping("/{id}")
    public R<?> delete(@PathVariable String id, @RequestParam long expectedRevision) {
        schedules.delete(id, expectedRevision);
        return R.ok();
    }

    @PostMapping("/{id}/enable")
    public R<?> enable(@PathVariable String id, @Valid @RequestBody Action action) {
        return R.ok(schedules.toggle(id, action.expectedRevision(), true));
    }

    @PostMapping("/{id}/disable")
    public R<?> disable(@PathVariable String id, @Valid @RequestBody Action action) {
        return R.ok(schedules.toggle(id, action.expectedRevision(), false));
    }

    @PostMapping("/{id}/run")
    public R<?> run(@PathVariable String id, @Valid @RequestBody Action action) {
        return R.ok(schedules.run(id, action.expectedRevision(), action.clientRequestId()));
    }

    @GetMapping("/{id}/runs")
    public R<?> runs(@PathVariable String id, @RequestParam(defaultValue = "1") @Min(1) int page,
                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(schedules.runs(id, page, size));
    }
}
