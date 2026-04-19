package com.schemaplexai.model.dto.knowledge;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/**
 * 知识文档上传请求（multipart 伴随字段）。
 *
 * <p>文件本身通过 {@code MultipartFile} 传递；本 DTO 仅承载结构化元数据与幂等键。
 */
@Data
public class UploadDocumentRequest {

    /**
     * 客户端自定义幂等键；为空时由服务端基于 (userId, contentSha256) 派生。
     */
    @Size(max = 128, message = "Idempotency-Key 长度不得超过 128")
    private String idempotencyKey;

    /**
     * 上传来源：web / api / mcp / import。不传默认为 web（Controller 层兜底）。
     */
    @Pattern(regexp = "web|api|mcp|import", message = "uploadChannel 必须为 web/api/mcp/import")
    private String uploadChannel;

    /**
     * 自定义文档标题；为空时使用脱敏后的文件名。
     */
    @Size(max = 200)
    private String title;

    /**
     * 扩展元数据，透传到 {@code sf_knowledge_document.metadata}。
     */
    private Map<String, Object> metadata;
}
