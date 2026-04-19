package com.schemaplexai.service.storage;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 基于 MinIO 的文档对象存储实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioDocumentStorageService implements DocumentStorageService {

    private static final DateTimeFormatter YEAR_FORMAT = DateTimeFormatter.ofPattern("yyyy");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("MM");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("dd");

    private final MinioClient minioClient;
    private final MinioProperties properties;

    @Override
    public String buildKnowledgeObjectKey(String tenantId, String documentId, String sanitizedFilename) {
        LocalDate today = LocalDate.now();
        return String.format("tenants/%s/knowledge/%s/%s/%s/%s/%s",
                tenantId,
                today.format(YEAR_FORMAT),
                today.format(MONTH_FORMAT),
                today.format(DAY_FORMAT),
                documentId,
                sanitizedFilename);
    }

    @Override
    public void putObject(String bucket, String objectKey, InputStream inputStream, long size, String contentType) {
        try {
            PutObjectArgs args = PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(inputStream, size, size > 0 ? -1 : 10L * 1024 * 1024)
                    .contentType(contentType != null ? contentType : "application/octet-stream")
                    .build();
            minioClient.putObject(args);
            log.debug("MinIO 上传对象成功: bucket={}, key={}, size={}", bucket, objectKey, size);
        } catch (Exception e) {
            log.error("MinIO 上传对象失败: bucket={}, key={}, error={}", bucket, objectKey, e.getMessage(), e);
            throw new BusinessException(ResultCode.FAIL, "对象存储写入失败");
        }
    }

    @Override
    public String getPresignedDownloadUrl(String bucket, String objectKey, int expirySeconds) {
        int expiry = expirySeconds > 0 ? expirySeconds : properties.getPresignExpirySeconds();
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .method(Method.GET)
                    .expiry(expiry, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            log.error("MinIO 预签名 URL 生成失败: bucket={}, key={}, error={}", bucket, objectKey, e.getMessage(), e);
            throw new BusinessException(ResultCode.FAIL, "生成下载链接失败");
        }
    }

    @Override
    public InputStream getObject(String bucket, String objectKey) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
        } catch (Exception e) {
            log.error("MinIO 读取对象失败: bucket={}, key={}, error={}", bucket, objectKey, e.getMessage(), e);
            throw new BusinessException(ResultCode.FAIL, "对象存储读取失败");
        }
    }

    @Override
    public void removeObject(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            log.debug("MinIO 删除对象成功: bucket={}, key={}", bucket, objectKey);
        } catch (ErrorResponseException e) {
            // 对象不存在忽略
            log.warn("MinIO 删除对象静默忽略: bucket={}, key={}, error={}", bucket, objectKey, e.errorResponse().code());
        } catch (Exception e) {
            log.warn("MinIO 删除对象失败: bucket={}, key={}, error={}", bucket, objectKey, e.getMessage());
        }
    }

    @Override
    public String getDefaultBucket() {
        return properties.getBucket();
    }
}
