package com.schemaplexai.dao.mapper.semantic;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.semantic.SchemaSnapshotEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Schema 快照租户限定 Mapper。 */
@Mapper
public interface SchemaSnapshotMapper extends BaseMapper<SchemaSnapshotEntity> {

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
