package com.schemaplexai.dao.mapper.semantic;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.schemaplexai.model.entity.semantic.SemanticModelEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 语义模型租户限定 Mapper。 */
@Mapper
public interface SemanticModelMapper extends BaseMapper<SemanticModelEntity> {

    @Select("""
            SELECT * FROM sf_semantic_model
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND id = CAST(#{modelId} AS UUID)
              AND deleted = 0
            LIMIT 1
            """)
    SemanticModelEntity selectOwned(
            @Param("tenantId") String tenantId,
            @Param("modelId") String modelId);

    @Select("""
            SELECT * FROM sf_semantic_model
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND deleted = 0
            ORDER BY updated_at DESC, created_at DESC
            """)
    List<SemanticModelEntity> selectAllOwned(@Param("tenantId") String tenantId);

    @Select("""
            SELECT COUNT(1) FROM sf_semantic_model
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND LOWER(name) = LOWER(#{name})
              AND deleted = 0
              AND (CAST(#{excludedModelId} AS VARCHAR) IS NULL OR id::text <> #{excludedModelId})
            """)
    long countOwnedName(
            @Param("tenantId") String tenantId,
            @Param("name") String name,
            @Param("excludedModelId") String excludedModelId);

    @Update("""
            UPDATE sf_semantic_model
            SET name = #{entity.name},
                domain = #{entity.domain},
                description = #{entity.description},
                active_version_id = CAST(#{entity.activeVersionId} AS UUID),
                status = #{entity.status},
                revision = #{entity.revision},
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = CAST(#{entity.tenantId} AS UUID)
              AND id = CAST(#{entity.id} AS UUID)
              AND revision = #{expectedRevision}
              AND deleted = 0
            """)
    int updateOwnedWithRevision(
            @Param("entity") SemanticModelEntity entity,
            @Param("expectedRevision") long expectedRevision);

    @Update("""
            UPDATE sf_semantic_model
            SET deleted = 1,
                status = 'archived',
                revision = revision + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE tenant_id = CAST(#{tenantId} AS UUID)
              AND id = CAST(#{modelId} AS UUID)
              AND revision = #{expectedRevision}
              AND active_version_id IS NULL
              AND deleted = 0
            """)
    int softDeleteOwned(
            @Param("tenantId") String tenantId,
            @Param("modelId") String modelId,
            @Param("expectedRevision") long expectedRevision);
}
