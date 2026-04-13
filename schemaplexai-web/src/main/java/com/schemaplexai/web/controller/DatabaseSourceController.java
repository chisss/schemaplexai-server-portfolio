package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.database.DatabaseQueryExecuteRequest;
import com.schemaplexai.model.dto.database.DatabaseSourceQueryRequest;
import com.schemaplexai.model.dto.database.DatabaseSourceSaveRequest;
import com.schemaplexai.model.vo.database.DatabaseQueryResultVO;
import com.schemaplexai.model.vo.database.DatabaseSourceTestVO;
import com.schemaplexai.model.vo.database.DatabaseSourceVO;
import com.schemaplexai.service.database.DatabaseSourceService;
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
 * 数据库数据源控制器
 */
@RestController
@RequestMapping("/database-sources")
@RequiredArgsConstructor
@Tag(name = "数据库数据源")
public class DatabaseSourceController {

    private final DatabaseSourceService databaseSourceService;

    @GetMapping
    @Operation(summary = "分页查询数据库数据源")
    public R<PageResult<DatabaseSourceVO>> page(DatabaseSourceQueryRequest request) {
        return R.ok(databaseSourceService.page(request));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取数据库数据源详情")
    public R<DatabaseSourceVO> getById(@PathVariable String id) {
        return R.ok(databaseSourceService.getById(id));
    }

    @PostMapping
    @Operation(summary = "创建数据库数据源")
    public R<DatabaseSourceVO> create(@Valid @RequestBody DatabaseSourceSaveRequest request) {
        return R.ok(databaseSourceService.create(request));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新数据库数据源")
    public R<DatabaseSourceVO> update(@PathVariable String id, @Valid @RequestBody DatabaseSourceSaveRequest request) {
        return R.ok(databaseSourceService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除数据库数据源")
    public R<Void> delete(@PathVariable String id) {
        databaseSourceService.delete(id);
        return R.ok();
    }

    @PostMapping("/test-draft")
    @Operation(summary = "测试草稿数据库数据源")
    public R<DatabaseSourceTestVO> testDraft(@Valid @RequestBody DatabaseSourceSaveRequest request) {
        return R.ok(databaseSourceService.testDraft(request));
    }

    @PostMapping("/{id}/test")
    @Operation(summary = "测试已保存数据库数据源")
    public R<DatabaseSourceTestVO> test(@PathVariable String id) {
        return R.ok(databaseSourceService.test(id));
    }

    @PostMapping("/{id}/query")
    @Operation(summary = "执行数据库查询")
    public R<DatabaseQueryResultVO> executeQuery(@PathVariable String id,
                                                 @Valid @RequestBody DatabaseQueryExecuteRequest request) {
        return R.ok(databaseSourceService.executeQuery(id, request));
    }
}
