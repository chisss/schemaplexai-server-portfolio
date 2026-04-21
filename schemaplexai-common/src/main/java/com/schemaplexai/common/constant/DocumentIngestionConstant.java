package com.schemaplexai.common.constant;

/**
 * 文档摄入管线常量
 */
public final class DocumentIngestionConstant {

    private DocumentIngestionConstant() {}

    // ── 向量元数据键 ──
    public static final String META_TENANT_ID = "tenant_id";
    public static final String META_CONTEXT_ID = "context_id";
    public static final String META_DOCUMENT_ID = "document_id";
    public static final String META_ITEM_ID = "item_id";
    public static final String META_CHUNK_INDEX = "chunk_index";
    public static final String META_FILE_NAME = "file_name";

    // ── 数据源类型 ──
    public static final String SOURCE_KNOWLEDGE_DOCUMENT = "knowledge_document";
    public static final String SOURCE_CONTEXT_ITEM = "context_item";

    // ── 操作类型 ──
    public static final String OPERATION_WRITE = "write";

    // ── 摄入结果状态 ──
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILED = "failed";
    public static final String RESULT_SKIPPED = "skipped";

    // ── 默认上传渠道 ──
    public static final String DEFAULT_UPLOAD_CHANNEL = "web";
}
