package com.schemaplexai.service.storage;

import java.io.InputStream;

/**
 * 文档对象存储服务抽象。
 *
 * <p>当前默认实现为 MinIO（{@link MinioDocumentStorageService}）。
 */
public interface DocumentStorageService {

    /**
     * 构造知识文档对象键。
     * <p>规则：{@code tenants/{tenantId}/knowledge/{yyyy}/{MM}/{dd}/{documentId}/{sanitizedFilename}}
     *
     * @param tenantId          租户 ID
     * @param documentId        文档 ID
     * @param sanitizedFilename 已脱敏的文件名
     * @return 对象键
     */
    String buildKnowledgeObjectKey(String tenantId, String documentId, String sanitizedFilename);

    /**
     * 以流式方式上传对象。
     *
     * @param bucket      bucket 名
     * @param objectKey   对象键
     * @param inputStream 输入流（调用方负责关闭）
     * @param size        对象大小（字节），未知时传 -1
     * @param contentType MIME 类型
     */
    void putObject(String bucket, String objectKey, InputStream inputStream, long size, String contentType);

    /**
     * 生成下载用预签名 URL。
     *
     * @param bucket         bucket 名
     * @param objectKey      对象键
     * @param expirySeconds  有效期（秒），<=0 时使用默认值
     * @return 预签名 URL
     */
    String getPresignedDownloadUrl(String bucket, String objectKey, int expirySeconds);

    /**
     * 获取对象输入流。调用方负责关闭返回的流。
     *
     * @param bucket    bucket 名
     * @param objectKey 对象键
     * @return 对象内容输入流
     */
    InputStream getObject(String bucket, String objectKey);

    /**
     * 删除对象；对象不存在时静默忽略。
     */
    void removeObject(String bucket, String objectKey);

    /**
     * 默认 bucket 名。
     */
    String getDefaultBucket();
}
