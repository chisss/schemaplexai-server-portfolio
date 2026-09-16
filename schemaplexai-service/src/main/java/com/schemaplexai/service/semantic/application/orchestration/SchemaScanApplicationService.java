package com.schemaplexai.service.semantic.application.orchestration;

import com.schemaplexai.common.exception.BusinessException;
import com.schemaplexai.common.result.ResultCode;
import com.schemaplexai.common.util.SecurityUtil;
import com.schemaplexai.service.semantic.application.command.ScanSchemaCommand;
import com.schemaplexai.service.semantic.domain.model.SchemaSnapshot;
import com.schemaplexai.service.semantic.domain.model.schema.DatabaseSchema;
import com.schemaplexai.service.semantic.domain.model.schema.SchemaScanScope;
import com.schemaplexai.service.semantic.domain.port.SchemaIntrospectorPort;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSession;
import com.schemaplexai.service.semantic.domain.port.SchemaMetadataSessionFactory;
import com.schemaplexai.service.semantic.domain.repository.SchemaSnapshotRepository;
import com.schemaplexai.service.semantic.domain.service.SchemaFingerprint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Schema 扫描、指纹和快照持久化编排。 */
@Service
public class SchemaScanApplicationService {

    private final SchemaMetadataSessionFactory sessionFactory;
    private final SchemaSnapshotRepository snapshotRepository;
    private final List<SchemaIntrospectorPort> introspectors;
    private final SchemaFingerprint fingerprint = new SchemaFingerprint();

    public SchemaScanApplicationService(
            SchemaMetadataSessionFactory sessionFactory,
            SchemaSnapshotRepository snapshotRepository,
            List<SchemaIntrospectorPort> introspectors) {
        this.sessionFactory = sessionFactory;
        this.snapshotRepository = snapshotRepository;
        this.introspectors = List.copyOf(introspectors);
    }

    public SchemaSnapshot scan(String sourceId, ScanSchemaCommand command) {
        String tenantId = requireTenantId();
        SchemaScanScope scope = command == null
                ? SchemaScanScope.all()
                : new SchemaScanScope(command.catalog(), command.schema(), command.includeObjects());
        try (SchemaMetadataSession session = sessionFactory.open(tenantId, sourceId)) {
            SchemaIntrospectorPort introspector = introspectors.stream()
                    .filter(candidate -> candidate.supports(session.databaseType()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            ResultCode.SCHEMA_SOURCE_UNSUPPORTED,
                            "暂不支持该数据库类型的 Schema 扫描"));
            DatabaseSchema schema = introspector.scan(scope, session);
            String value = fingerprint.calculate(schema);
            return snapshotRepository.findByFingerprint(tenantId, sourceId, value)
                    .orElseGet(() -> persist(tenantId, sourceId, value, schema));
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ResultCode.SCHEMA_SCAN_FAILED, "Schema 扫描失败，请检查数据源元数据权限");
        }
    }

    public List<SchemaSnapshot> list(String sourceId) {
        return snapshotRepository.findAllBySource(requireTenantId(), sourceId);
    }

    private SchemaSnapshot persist(
            String tenantId,
            String sourceId,
            String value,
            DatabaseSchema schema) {
        SchemaSnapshot snapshot = SchemaSnapshot.create(
                UUID.randomUUID().toString(),
                tenantId,
                sourceId,
                value,
                schema,
                LocalDateTime.now());
        try {
            snapshotRepository.insert(snapshot);
            return snapshot;
        } catch (DuplicateKeyException exception) {
            return snapshotRepository.findByFingerprint(tenantId, sourceId, value)
                    .orElseThrow(() -> exception);
        }
    }

    private String requireTenantId() {
        String tenantId = SecurityUtil.getCurrentTenantId();
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "租户上下文缺失");
        }
        return tenantId.trim();
    }
}
