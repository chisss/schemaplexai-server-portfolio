package com.schemaplexai.service.rag.ingest.parse;

import com.schemaplexai.service.rag.RagRuntimeSettings;
import com.schemaplexai.service.rag.ingest.IngestCategory;

/**
 * 解析入参。只暴露解析器需要的最小信息，避免 handler 反向依赖 DB/MinIO。
 *
 * @param fileName   原始文件名（用于锚点与提示）
 * @param mimeType   网关嗅探出的真实 MIME
 * @param category   网关判定的分类
 * @param content    文件字节（网关已做容量上限，这里可安全整块读）
 * @param settings   租户运行时配置
 */
public record ParseContext(String fileName,
                           String mimeType,
                           IngestCategory category,
                           byte[] content,
                           RagRuntimeSettings settings) {

    public ParseContext withCategory(IngestCategory newCategory) {
        return new ParseContext(fileName, mimeType, newCategory, content, settings);
    }
}
