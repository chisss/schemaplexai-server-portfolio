package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.PageResult;
import com.schemaplexai.common.result.R;
import com.schemaplexai.model.dto.database.DatabaseSourceQueryRequest;
import com.schemaplexai.model.vo.database.DatabaseSourceVO;
import com.schemaplexai.service.database.DatabaseSourceService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequiredArgsConstructor
public class OntologySchemaSourceController {

    private final DatabaseSourceService sources;

    public record SourceOption(String id, String name, String databaseType, String database, String schema) {
        private static SourceOption of(DatabaseSourceVO source) {
            return new SourceOption(source.getId(), source.getName(), source.getDatabaseType(),
                    source.getDatabase(), source.getSchema());
        }
    }

    @GetMapping("/semantic/schema-sources")
    @PreAuthorize("@authorizationService.hasPermission('database:source:view')")
    public R<PageResult<SourceOption>> list(@RequestParam(defaultValue = "1") @Min(1) @Max(100000) int page,
                                           @RequestParam(defaultValue = "100") @Min(1) @Max(200) int size,
                                           @RequestParam(required = false) @Size(max = 100) String keyword) {
        var request = new DatabaseSourceQueryRequest();
        request.setPage(page);
        request.setSize(size);
        request.setKeyword(keyword);
        var matches = sources.page(request);
        return R.ok(new PageResult<>(matches.getRecords().stream().map(SourceOption::of).toList(),
                matches.getTotal(), matches.getCurrent(), matches.getSize()));
    }
}
