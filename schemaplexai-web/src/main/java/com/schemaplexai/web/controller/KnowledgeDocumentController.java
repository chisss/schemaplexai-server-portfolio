package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.model.dto.knowledge.UploadDocumentRequest;
import com.schemaplexai.model.dto.security.SecurityAuditContext;
import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import com.schemaplexai.service.idempotency.IdempotencyKeyService;
import com.schemaplexai.service.knowledge.KnowledgeDocumentService;
import com.schemaplexai.web.util.RequestContextUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识文档管理控制器
 */
@RestController
@RequestMapping("/knowledge/documents")
@RequiredArgsConstructor
@Tag(name = "知识文档管理")
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;
    private final IdempotencyKeyService idempotencyKeyService;

    @PostMapping("/upload")
    @Operation(summary = "上传文档并启动 RAG 摄入")
    public R<KnowledgeDocumentVO> upload(@RequestParam String contextId,
                                         @RequestParam("file") MultipartFile file,
                                         @Validated UploadDocumentRequest request,
                                         HttpServletRequest httpRequest) {
        // 幂等键校验
        String idempotencyKey = request.getIdempotencyKey();
        if (StringUtils.hasText(idempotencyKey)) {
            String redisKey = IdempotencyKeyService.buildUploadKey(SecurityUtil.getCurrentTenantId(), idempotencyKey);
            if (!idempotencyKeyService.tryAcquire(redisKey)) {
                return R.ok(null);
            }
        }

        SecurityAuditContext auditContext = RequestContextUtils.buildSecurityAuditContext(httpRequest);
        return R.ok(knowledgeDocumentService.uploadDocument(contextId, file, request, auditContext));
    }

    @GetMapping
    @Operation(summary = "查询上下文下的知识文档列表")
    public R<List<KnowledgeDocumentVO>> listByContextId(@RequestParam String contextId) {
        return R.ok(knowledgeDocumentService.listByContextId(contextId));
    }

    @GetMapping("/{documentId}")
    @Operation(summary = "查询文档处理状态")
    public R<KnowledgeDocumentVO> getStatus(@PathVariable String documentId) {
        return R.ok(knowledgeDocumentService.getDocumentStatus(documentId));
    }

    @GetMapping("/{documentId}/download-url")
    @Operation(summary = "获取文档下载预签名 URL")
    public R<String> getDownloadUrl(@PathVariable String documentId) {
        return R.ok(knowledgeDocumentService.getDownloadUrl(documentId));
    }
}
