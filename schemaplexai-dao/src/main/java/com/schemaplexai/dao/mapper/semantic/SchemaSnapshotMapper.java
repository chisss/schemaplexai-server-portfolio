package com.schemaplexai.dao.mapper.semantic;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.semantic.SchemaSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Schema 快照租户限定 Mapper。 */
@Mapper
public interface SchemaSnapshotMapper extends BaseMapper<SchemaSnapshotEntity> {

    @Insert("""
            INSERT INTO sf_schema_snapshot
                (id, tenant_id, source_id, fingerprint, schema_json, status, scanned_at, created_by, updated_by)
            VALUES (CAST(#{entity.id} AS UUID), CAST(#{entity.tenantId} AS UUID), CAST(#{entity.sourceId} AS UUID),
                    #{entity.fingerprint}, CAST(#{schemaJson} AS JSONB), #{entity.status}, #{entity.scannedAt},
                    CAST(#{entity.createdBy} AS UUID), CAST(#{entity.updatedBy} AS UUID))
            """)
    void insertOwned(@Param("entity") SchemaSnapshotEntity entity, @Param("schemaJson") String schemaJson);

    @ResultMap("mybatis-plus_SchemaSnapshotEntity")
    @Select("""
            SELECT * FROM sf_schema_snapshot
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND id = CAST(#{snapshotId} AS UUID)
              AND deleted = 0
            LIMIT 1
            """)
    SchemaSnapshotEntity selectOwned(
            @Param("tenantId") String tenantId,
            @Param("snapshotId") String snapshotId);

    @ResultMap("mybatis-plus_SchemaSnapshotEntity")
    @Select("""
            SELECT * FROM sf_schema_snapshot
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND source_id = CAST(#{sourceId} AS UUID)
              AND fingerprint = #{fingerprint}
              AND deleted = 0
            LIMIT 1
            """)
    SchemaSnapshotEntity selectByFingerprint(
            @Param("tenantId") String tenantId,
            @Param("sourceId") String sourceId,
            @Param("fingerprint") String fingerprint);

    @ResultMap("mybatis-plus_SchemaSnapshotEntity")
    @Select("""
            SELECT * FROM sf_schema_snapshot
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND source_id = CAST(#{sourceId} AS UUID)
              AND deleted = 0
            ORDER BY scanned_at DESC, created_at DESC
            """)
    List<SchemaSnapshotEntity> selectAllBySource(
            @Param("tenantId") String tenantId,
            @Param("sourceId") String sourceId);
}
