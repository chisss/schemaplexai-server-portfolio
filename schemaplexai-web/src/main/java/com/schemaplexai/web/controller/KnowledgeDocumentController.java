package com.schemaplexai.web.controller;

import com.schemaplexai.common.result.R;
import com.schemaplexai.model.vo.knowledge.KnowledgeDocumentVO;
import com.schemaplexai.service.knowledge.KnowledgeDocumentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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

    @PostMapping("/upload")
    @Operation(summary = "上传文档并启动 RAG 摄入")
    public R<KnowledgeDocumentVO> upload(@RequestParam String contextId,
                                         @RequestParam("file") MultipartFile file) {
        return R.ok(knowledgeDocumentService.uploadDocument(contextId, file));
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
}
