package com.schemaplexai.service.desktop;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.FilenameSanitizer;
import com.schemaplexai.service.desktop.DesktopEmployeeAccess.Employee;
import com.schemaplexai.service.storage.DocumentStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DesktopAttachmentService {
    private static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_ATTACHMENTS = 10;
    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Tika TIKA = new Tika();
    private final JdbcTemplate jdbc;
    private final DocumentStorageService storage;

    public record Attachment(String id, String name, String mimeType, long byteSize) {}
    public record ChatAttachment(String fileId, String fileName, String fileUrl) {}

    public Attachment upload(String originalName, InputStream body, long size) {
        Employee employee = Employee.current();
        String id = UUID.randomUUID().toString();
        String name = FilenameSanitizer.sanitize(originalName);
        String objectKey = tenantPrefix(employee.tenantId()) + "desktop-chat/" + id + "/" + name;
        return register(employee, id, name, objectKey, body, size);
    }

    public ChatAttachment uploadChat(String originalName, InputStream body, long size) {
        Employee employee = Employee.current();
        String id = UUID.randomUUID().toString();
        String name = FilenameSanitizer.sanitize(originalName);
        String objectKey = tenantPrefix(employee.tenantId()) + "chat/" + LocalDate.now().format(DATE_PATH)
                + "/" + id + "/" + name;
        Attachment attachment = register(employee, id, name, objectKey, body, size);
        return new ChatAttachment(objectKey, attachment.name(),
                storage.getPresignedDownloadUrl(storage.getDefaultBucket(), objectKey, 3600));
    }

    private Attachment register(Employee employee, String id, String name, String objectKey, InputStream body, long size) {
        if (size < 1 || size > MAX_BYTES) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "附件大小应在 1 B 至 20 MiB 之间");
        }
        byte[] bytes;
        try {
            bytes = body.readNBytes(MAX_BYTES + 1);
        } catch (IOException exception) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "读取上传文件失败");
        }
        if (bytes.length != size) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "附件大小与上传内容不符");
        }
        String mime = TIKA.detect(bytes, name);
        String bucket = storage.getDefaultBucket();
        storage.putObject(bucket, objectKey, new ByteArrayInputStream(bytes), bytes.length, mime);
        try {
            jdbc.update("""
                    INSERT INTO sf_desktop_attachment(id,tenant_id,owner_id,object_key,name,mime_type,byte_size,expires_at)
                    VALUES(?,?,?,?,?,?,?,now()+interval '30 days')
                    """, id, employee.tenantId(), employee.userId(), objectKey, name, mime, bytes.length);
        } catch (RuntimeException failure) {
            try {
                storage.removeObject(bucket, objectKey);
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                log.warn("清理未登记的对话附件失败: attachmentId={}", id);
            }
            throw failure;
        }
        return new Attachment(id, name, mime, bytes.length);
    }

    public void validateObjectKeys(List<String> keys, boolean workspace) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Employee employee = Employee.current();
        validateObjectKeys(keys, workspace, employee.tenantId(), employee.userId());
    }

    public void validateObjectKeys(List<String> keys, boolean workspace, String tenantId, String ownerId) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        if (tenantId == null || ownerId == null || tenantId.isBlank() || ownerId.isBlank()) {
            throw forbidden();
        }
        if (keys.size() > MAX_ATTACHMENTS || new HashSet<>(keys).size() != keys.size()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "单次最多使用 10 个不同附件");
        }
        long totalBytes = 0;
        for (String key : keys) {
            totalBytes += authorizedSize(key, workspace, tenantId, ownerId);
            if (totalBytes > MAX_BYTES) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "单次附件总大小不能超过 20 MiB");
            }
        }
    }

    private long authorizedSize(String key, boolean workspace, String tenantId, String ownerId) {
        String prefix = tenantPrefix(tenantId);
        if (key == null || !(key.startsWith(prefix + "chat/") || (workspace && key.startsWith(prefix + "desktop-chat/")))) {
            throw forbidden();
        }
        int lastSlash = key.lastIndexOf('/');
        String id = key.substring(key.lastIndexOf('/', lastSlash - 1) + 1, lastSlash);
        if (id.length() != 36) {
            throw forbidden();
        }
        Long bytes = jdbc.queryForObject("""
                SELECT COALESCE((SELECT byte_size FROM sf_desktop_attachment WHERE id=? AND object_key=?
                    AND tenant_id=? AND owner_id=? AND expires_at>now() AND byte_size>0
                    AND mime_type IS NOT NULL AND mime_type<>''), -1)
                """, Long.class, id, key, tenantId, ownerId);
        if (bytes == null || bytes < 1 || bytes > MAX_BYTES) {
            throw forbidden();
        }
        return bytes;
    }

    private BusinessException forbidden() {
        return new BusinessException(ResultCode.FORBIDDEN, "当前会话无权使用此附件。请检查权限或重新上传已过期附件");
    }

    private String tenantPrefix(String tenantId) {
        return "tenants/" + tenantId + "/";
    }

    public List<String> objectKeys(List<String> ids) {
        Employee employee = Employee.current();
        if (ids == null) return List.of();
        return ids.stream().map(id -> DesktopWorkspaceService.single(jdbc.query("""
                SELECT object_key FROM sf_desktop_attachment
                WHERE id=? AND tenant_id=? AND owner_id=? AND expires_at>now()
                """, (rs, i) -> rs.getString(1), id, employee.tenantId(), employee.userId()))).toList();
    }
}
