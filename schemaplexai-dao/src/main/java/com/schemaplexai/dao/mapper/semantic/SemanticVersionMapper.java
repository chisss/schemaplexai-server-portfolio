package com.schemaplexai.dao.mapper.semantic;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.semantic.SemanticVersionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 语义版本租户限定 Mapper。 */
@Mapper
public interface SemanticVersionMapper extends BaseMapper<SemanticVersionEntity> {

    @Select("""
            SELECT * FROM sf_semantic_version
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND model_id = CAST(#{modelId} AS UUID)
              AND id = CAST(#{versionId} AS UUID)
              AND deleted = 0
            LIMIT 1
            """)
    SemanticVersionEntity selectOwned(
            @Param("tenantId") String tenantId,
            @Param("modelId") String modelId,
            @Param("versionId") String versionId);

    @Select("""
            SELECT * FROM sf_semantic_version
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND id = CAST(#{versionId} AS UUID)
              AND deleted = 0
            LIMIT 1
            """)
    SemanticVersionEntity selectOwnedById(
            @Param("tenantId") String tenantId,
            @Param("versionId") String versionId);

    @Select("""
            SELECT * FROM sf_semantic_version
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND model_id = CAST(#{modelId} AS UUID)
              AND deleted = 0
            ORDER BY version_no DESC
            """)
    List<SemanticVersionEntity> selectAllOwned(
            @Param("tenantId") String tenantId,
            @Param("modelId") String modelId);

    @Select("""
            WITH locked_model AS MATERIALIZED (
                SELECT id FROM sf_semantic_model
                WHERE tenant_id = CAST(#{tenantId} AS UUID)
                  AND id = CAST(#{modelId} AS UUID)
                  AND deleted = 0
                FOR UPDATE
            )
            SELECT COALESCE(MAX(version.version_no), 0)
            FROM locked_model model
            LEFT JOIN sf_semantic_version version
              ON version.tenant_id = CAST(#{tenantId} AS UUID)
             AND version.model_id = model.id
             AND version.deleted = 0
            """)
    int selectMaxVersionNo(
            @Param("tenantId") String tenantId,
            @Param("modelId") String modelId);

    @Update("""
            UPDATE sf_semantic_version
            SET status = #{entity.status},
                source_snapshot_id = CAST(#{entity.sourceSnapshotId} AS UUID),
                checksum = #{entity.checksum},
                triple_count = #{entity.tripleCount},
                validation_report = #{entity.validationReport},
                published_at = #{entity.publishedAt},
                revision = #{entity.revision},
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = CAST(#{entity.tenantId} AS UUID)
              AND model_id = CAST(#{entity.modelId} AS UUID)
              AND id = CAST(#{entity.id} AS UUID)
              AND revision = #{expectedRevision}
              AND deleted = 0
            """)
    int updateOwnedWithRevision(
            @Param("entity") SemanticVersionEntity entity,
            @Param("expectedRevision") long expectedRevision);
}
