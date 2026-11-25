package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.service.desktop.DesktopWorkflowService;
import com.schemaplexai.service.workflow.WorkflowInstanceService;
import com.schemaplexai.service.workflow.WorkflowTemplateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Validated
@RestController
@RequestMapping("/desktop/workflows")
@RequiredArgsConstructor
public class DesktopWorkflowController {
    private final DesktopWorkflowService workflows;
    private final WorkflowTemplateService templates;
    private final WorkflowInstanceService instances;

    public record RunRequest(@NotBlank @Size(max = 160) String name, Map<String, Object> variables) {}
    public record GrantRequest(@NotBlank @Size(max = 64) String userId) {}

    @PutMapping("/{id}/grants")
    public R<Void> grant(@PathVariable String id, @Valid @RequestBody GrantRequest request) {
        workflows.grant(id, request.userId());
        return R.ok();
    }

    @DeleteMapping("/{id}/grants/{userId}")
    public R<Void> revoke(@PathVariable String id, @PathVariable String userId) {
        workflows.revoke(id, userId);
        return R.ok();
    }

    @GetMapping
    public R<?> list(@RequestParam(required = false) @Size(max = 100) String keyword,
                     @RequestParam(required = false) String status,
                     @RequestParam(defaultValue = "1") @Min(1) int page,
                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(workflows.list(keyword, status, page, size));
    }

    @GetMapping("/{id}")
    public R<?> detail(@PathVariable String id) {
        workflows.requireTemplate(id, false);
        return R.ok(templates.getById(id));
    }

    @GetMapping("/{id}/runs")
    public R<?> runs(@PathVariable String id, @RequestParam(defaultValue = "1") @Min(1) int page,
                     @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(workflows.runs(id, page, size));
    }

    @PostMapping("/{id}/runs")
    public R<?> run(@PathVariable String id, @Valid @RequestBody RunRequest request) {
        return R.ok(workflows.run(id, request.name(), request.variables()));
    }

    @GetMapping("/runs/{id}")
    public R<?> execution(@PathVariable String id) {
        workflows.ownRun(id);
        return R.ok(instances.getById(id));
    }

    @GetMapping("/runs/{id}/nodes")
    public R<?> nodes(@PathVariable String id) {
        workflows.ownRun(id);
        return R.ok(instances.getNodeExecutions(id));
    }
}
