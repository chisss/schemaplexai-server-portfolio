package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.vo.artifact.ArtifactVO;
import com.schemaplexai.service.artifact.ArtifactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 产物管理控制器
 */
@RestController
@RequestMapping("/artifacts")
@RequiredArgsConstructor
@Tag(name = "统一产物管理")
public class ArtifactController {

    private final ArtifactService artifactService;

    @GetMapping("/{id}")
    @Operation(summary = "获取产物详情")
    public R<ArtifactVO> getById(@PathVariable String id) {
        return R.ok(artifactService.getById(id));
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "下载产物")
    public ResponseEntity<ByteArrayResource> download(@PathVariable String id) {
        ArtifactService.DownloadPayload payload = artifactService.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(payload.fileName(), StandardCharsets.UTF_8))
                .contentType(MediaType.parseMediaType(payload.mimeType()))
                .body(new ByteArrayResource(payload.content()));
    }
}
