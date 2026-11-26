package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.service.desktop.DesktopDataService;
import com.schemaplexai.service.semantic.application.orchestration.CompositeQueryPlanner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
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
@RequestMapping("/desktop/data")
@RequiredArgsConstructor
public class DesktopDataController {
    private final DesktopDataService data;
    private final CompositeQueryPlanner planner;
    private final QueryProgressStream streams;

    public record Create(@NotBlank @Size(max = 128) String clientRequestId) {}
    public record Execute(@NotBlank @Size(max = 128) String planHash) {}

    @GetMapping("/datasets")
    @PreAuthorize("@authorizationService.hasPermission('database:source:manage')")
    public R<?> manageDatasets(@RequestParam(defaultValue = "1") @Min(1) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(data.manageDatasets(page, size));
    }

    @PostMapping("/datasets")
    @PreAuthorize("@authorizationService.hasPermission('database:source:manage')")
    public R<?> createDataset(@Valid @RequestBody DesktopDataService.DatasetDefinition request) {
        return R.ok(data.saveDataset(null, request));
    }

    @PutMapping("/datasets/{id}")
    @PreAuthorize("@authorizationService.hasPermission('database:source:manage')")
    public R<?> updateDataset(@PathVariable String id,
                              @Valid @RequestBody DesktopDataService.DatasetDefinition request) {
        return R.ok(data.saveDataset(id, request));
    }

    @PutMapping("/datasets/{id}/grants")
    @PreAuthorize("@authorizationService.hasPermission('database:source:manage')")
    public R<Void> grant(@PathVariable String id, @Valid @RequestBody DesktopDataService.DatasetGrant request) {
        data.grantDataset(id, request);
        return R.ok();
    }

    @DeleteMapping("/datasets/{id}/grants/{userId}")
    @PreAuthorize("@authorizationService.hasPermission('database:source:manage')")
    public R<Void> revoke(@PathVariable String id, @PathVariable String userId) {
        data.revokeDataset(id, userId);
        return R.ok();
    }

    @GetMapping("/catalog")
    public R<?> catalog() { return R.ok(data.catalog()); }

    @PostMapping("/conversations")
    public R<?> create(@Valid @RequestBody Create request) { return R.ok(data.create(request.clientRequestId())); }

    @GetMapping("/conversations/{id}/queries")
    public R<?> history(@PathVariable String id, @RequestParam(defaultValue = "1") @Min(1) int page,
                        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(data.history(id, page, size));
    }

    @GetMapping("/conversations/{id}/queries/turns")
    public R<?> turns(@PathVariable String id, @RequestParam(defaultValue = "1") @Min(1) int page,
                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return R.ok(data.turns(id, page, size));
    }

    @PostMapping(value = "/conversations/{id}/queries/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("@authorizationService.hasPermission('self:service') and @authorizationService.hasPermission('semantic:query:interpret')")
    public SseEmitter askStream(@PathVariable String id, @Valid @RequestBody DesktopDataService.Ask request) {
        return streams.start(send -> {
            var turn = data.askTurn(id, request, planner, message -> send.accept(
                    Map.of("type", "progress", "message", message)));
            turn.queries().forEach(query -> send.accept(Map.of("type", "query", "query", query)));
            send.accept(Map.of("type", "complete", "turn", turn, "presentation", turn.presentation()));
        });
    }

    @PostMapping("/conversations/{id}/queries")
    public R<?> ask(@PathVariable String id, @Valid @RequestBody DesktopDataService.Ask request) {
        return R.ok(data.ask(id, request));
    }

    @PostMapping("/conversations/{id}/queries/{queryId}/execute")
    public R<?> execute(@PathVariable String id, @PathVariable String queryId, @Valid @RequestBody Execute command) {
        return R.ok(data.execute(id, queryId, command.planHash()));
    }

    @GetMapping("/results/{id}")
    public R<?> result(@PathVariable String id) { return R.ok(data.result(id)); }

    @GetMapping("/results/{id}/rows")
    public R<?> rows(@PathVariable String id, @RequestParam(defaultValue = "1") @Min(1) int page,
                     @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size) {
        return R.ok(data.rows(id, page, size));
    }

    @PostMapping("/exports")
    public R<?> export(@Valid @RequestBody DesktopDataService.ExportRequest request) {
        return R.ok(data.export(request));
    }

    @GetMapping("/exports/{id}")
    public R<?> exportDetail(@PathVariable String id) { return R.ok(data.download(id, false).export()); }

    @GetMapping("/exports/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        var file = data.download(id, true);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, file.export().mimeType())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.export().filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }
}
